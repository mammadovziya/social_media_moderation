from __future__ import annotations

import base64
import binascii
import hashlib
import struct
import threading
from collections import OrderedDict
from dataclasses import dataclass

import cv2
import numpy as np

from .config import (
    ALGORITHM_VERSION,
    CANONICALIZATION_VERSION,
    CANDIDATE_SELECTION_VERSION,
    DESCRIPTOR_BYTES,
    DESCRIPTOR_SCHEMA_VERSION,
    EXCLUSION_MASK_VERSION,
    LOWE_RATIO,
    LSH_KEY_SIZE,
    LSH_MAX_HAMMING_DISTANCE,
    LSH_MULTI_PROBE_LEVEL,
    LSH_TABLE_NUMBER,
    MAX_CACHED_REVISIONS,
    MAX_RERANK_CANDIDATES,
    MAX_TOTAL_DESCRIPTORS_PER_SNAPSHOT,
    MAX_WORKING_DIMENSION,
    MIN_HOMOGRAPHY_INLIERS,
    MIN_INLIER_RATIO,
    MIN_ORB_SPECIFICITY_INLIER_LEAD,
    MIN_QUERY_DESCRIPTORS,
    MIN_RATIO_MATCHES,
    MIN_REFERENCE_DESCRIPTORS,
    MIN_SOURCE_DIMENSION,
    ORB_MAX_FEATURES,
    RANSAC_CONFIDENCE,
    RANSAC_MAX_ITERATIONS,
    RANSAC_REPROJECTION_PIXELS,
)
from .errors import (
    CapacityError,
    InvalidDescriptorError,
    RevisionNotLoadedError,
    SnapshotConflictError,
)
from .image_features import Deadline, ImageFeatures
from .models import (
    DescriptorChannel,
    DescriptorPayload,
    MatchCandidate,
    QueryResponse,
    RefreshRequest,
    RefreshResponse,
)


@dataclass(frozen=True, slots=True)
class ReferenceFeatures:
    reference_id: str
    channel: DescriptorChannel
    source_sha256: str
    exclusion_mask_sha256: str | None
    descriptors: np.ndarray
    points: np.ndarray
    working_width: int
    working_height: int


@dataclass(frozen=True, slots=True)
class RankedEvidence:
    reference_id: str
    channel: DescriptorChannel
    lsh_votes: int
    ratio_matches: int
    homography_inliers: int
    inlier_ratio: float
    median_hamming_distance: float


class ChannelIndex:
    __slots__ = (
        "channel",
        "references",
        "descriptor_count",
        "_descriptors",
        "_descriptor_owners",
        "_matcher",
        "_matcher_lock",
    )

    def __init__(
        self,
        channel: DescriptorChannel,
        references: tuple[ReferenceFeatures, ...],
        descriptors: np.ndarray,
        descriptor_owners: np.ndarray,
    ) -> None:
        self.channel = channel
        self.references = references
        self.descriptor_count = int(descriptors.shape[0])
        self._descriptors = descriptors
        self._descriptor_owners = descriptor_owners
        self._matcher_lock = threading.Lock()
        self._matcher = cv2.FlannBasedMatcher(
            {
                "algorithm": 6,
                "table_number": LSH_TABLE_NUMBER,
                "key_size": LSH_KEY_SIZE,
                "multi_probe_level": LSH_MULTI_PROBE_LEVEL,
            },
            {"checks": 64},
        )
        self._matcher.add([descriptors])
        self._matcher.train()

    def shortlist(self, query_descriptors: np.ndarray) -> list[tuple[int, int]]:
        with self._matcher_lock:
            nearest = self._matcher.knnMatch(query_descriptors, k=2)
        votes: dict[int, int] = {}
        best_distance: dict[int, int] = {}
        for neighbors in nearest:
            if not neighbors:
                continue
            match = neighbors[0]
            distance = int(match.distance)
            if distance > LSH_MAX_HAMMING_DISTANCE:
                continue
            owner = int(self._descriptor_owners[match.trainIdx])
            votes[owner] = votes.get(owner, 0) + 1
            best_distance[owner] = min(best_distance.get(owner, 257), distance)
        ranked = sorted(
            votes,
            key=lambda owner: (-votes[owner], best_distance[owner], owner),
        )
        return [(owner, votes[owner]) for owner in ranked[:MAX_RERANK_CANDIDATES]]


class IndexSnapshot:
    __slots__ = ("revision", "digest", "channels", "reference_count", "descriptor_count")

    def __init__(
        self,
        revision: str,
        digest: str,
        channels: dict[DescriptorChannel, ChannelIndex],
    ) -> None:
        self.revision = revision
        self.digest = digest
        self.channels = channels
        self.reference_count = len(
            {
                reference.reference_id
                for index in channels.values()
                for reference in index.references
            }
        )
        self.descriptor_count = sum(index.descriptor_count for index in channels.values())


def _validate_payload(payload: DescriptorPayload) -> tuple[np.ndarray, np.ndarray]:
    algorithm = payload.algorithm
    expected_algorithm_values = {
        "name": "ORB",
        "algorithm_version": ALGORITHM_VERSION,
        "implementation": "OpenCV",
        "implementation_version": cv2.__version__,
        "canonicalization_version": CANONICALIZATION_VERSION,
        "descriptor_type": "binary-uint8",
        "descriptor_bytes": DESCRIPTOR_BYTES,
        "max_features": ORB_MAX_FEATURES,
    }
    actual_algorithm_values = {
        "name": algorithm.name,
        "algorithm_version": algorithm.algorithm_version,
        "implementation": algorithm.implementation,
        "implementation_version": algorithm.implementation_version,
        "canonicalization_version": algorithm.canonicalization_version,
        "descriptor_type": algorithm.descriptor_type,
        "descriptor_bytes": algorithm.descriptor_bytes,
        "max_features": algorithm.max_features,
    }
    if payload.schema_version != DESCRIPTOR_SCHEMA_VERSION:
        raise InvalidDescriptorError("descriptor schema version is not supported")
    if actual_algorithm_values != expected_algorithm_values:
        raise InvalidDescriptorError("descriptor algorithm profile does not match this service")
    if payload.channel == "BACKGROUND":
        if payload.exclusion_mask_version != EXCLUSION_MASK_VERSION:
            raise InvalidDescriptorError("BACKGROUND descriptor mask version is not supported")
        if payload.exclusion_mask_sha256 is None:
            raise InvalidDescriptorError("BACKGROUND descriptor requires an exclusion mask digest")
    elif payload.exclusion_mask_version is not None or payload.exclusion_mask_sha256 is not None:
        raise InvalidDescriptorError("UNMASKED descriptor must not contain exclusion mask metadata")
    if not (
        MIN_SOURCE_DIMENSION <= payload.working_width <= MAX_WORKING_DIMENSION
        and MIN_SOURCE_DIMENSION <= payload.working_height <= MAX_WORKING_DIMENSION
    ):
        raise InvalidDescriptorError("working dimensions are outside the compiled profile")
    if payload.keypoint_count != len(payload.keypoints):
        raise InvalidDescriptorError("keypointCount does not match the keypoint array")
    if not MIN_REFERENCE_DESCRIPTORS <= payload.keypoint_count <= ORB_MAX_FEATURES:
        raise InvalidDescriptorError(
            f"active references require {MIN_REFERENCE_DESCRIPTORS} to "
            f"{ORB_MAX_FEATURES} descriptors"
        )
    if not payload.usable:
        raise InvalidDescriptorError("active reference descriptor is marked unusable")

    try:
        descriptor_bytes = base64.b64decode(payload.descriptors_base64, validate=True)
    except (binascii.Error, ValueError) as exc:
        raise InvalidDescriptorError("descriptorsBase64 is not strict base64") from exc
    expected_size = payload.keypoint_count * DESCRIPTOR_BYTES
    if len(descriptor_bytes) != expected_size:
        raise InvalidDescriptorError("decoded descriptor byte length does not match keypointCount")
    if hashlib.sha256(descriptor_bytes).hexdigest() != payload.descriptor_sha256:
        raise InvalidDescriptorError("descriptorSha256 does not match the descriptor bytes")

    descriptors = np.frombuffer(descriptor_bytes, dtype=np.uint8).reshape(
        payload.keypoint_count, DESCRIPTOR_BYTES
    )
    descriptors = np.ascontiguousarray(descriptors)
    normalized = np.asarray(payload.keypoints, dtype=np.float32)
    if normalized.shape != (payload.keypoint_count, 2) or not np.isfinite(normalized).all():
        raise InvalidDescriptorError("normalized keypoint array is malformed")
    if (normalized < 0.0).any() or (normalized > 1.0).any():
        raise InvalidDescriptorError("normalized keypoints are outside [0, 1]")
    points = normalized * np.asarray(
        [payload.working_width - 1, payload.working_height - 1], dtype=np.float32
    )
    return descriptors, np.ascontiguousarray(points)


def _snapshot_digest(references: list[ReferenceFeatures]) -> str:
    digest = hashlib.sha256()
    digest.update(b"visual-retrieval-snapshot/v1\0")
    digest.update(ALGORITHM_VERSION.encode("ascii"))
    for reference in sorted(
        references, key=lambda item: (item.channel, item.reference_id)
    ):
        encoded_id = reference.reference_id.encode("utf-8")
        digest.update(reference.channel.encode("ascii"))
        digest.update(struct.pack(">H", len(encoded_id)))
        digest.update(encoded_id)
        digest.update(bytes.fromhex(reference.source_sha256))
        if reference.exclusion_mask_sha256 is not None:
            digest.update(bytes.fromhex(reference.exclusion_mask_sha256))
        digest.update(
            struct.pack(
                ">III",
                reference.working_width,
                reference.working_height,
                reference.descriptors.shape[0],
            )
        )
        digest.update(reference.descriptors.tobytes(order="C"))
        digest.update(reference.points.astype("<f4", copy=False).tobytes(order="C"))
    return digest.hexdigest()


def _build_channel_index(
    channel: DescriptorChannel,
    references: list[ReferenceFeatures],
    deadline: Deadline,
) -> ChannelIndex:
    references.sort(key=lambda item: item.reference_id)
    combined = np.ascontiguousarray(
        np.concatenate([item.descriptors for item in references], axis=0), dtype=np.uint8
    )
    owners = np.empty((combined.shape[0],), dtype=np.int32)
    immutable_references: list[ReferenceFeatures] = []
    offset = 0
    for owner, item in enumerate(references):
        count = item.descriptors.shape[0]
        descriptors = combined[offset : offset + count]
        descriptors.setflags(write=False)
        item.points.setflags(write=False)
        owners[offset : offset + count] = owner
        immutable_references.append(
            ReferenceFeatures(
                reference_id=item.reference_id,
                channel=item.channel,
                source_sha256=item.source_sha256,
                exclusion_mask_sha256=item.exclusion_mask_sha256,
                descriptors=descriptors,
                points=item.points,
                working_width=item.working_width,
                working_height=item.working_height,
            )
        )
        offset += count
    combined.setflags(write=False)
    owners.setflags(write=False)
    deadline.check()
    index = ChannelIndex(channel, tuple(immutable_references), combined, owners)
    deadline.check()
    return index


def build_snapshot(request: RefreshRequest, deadline: Deadline) -> IndexSnapshot:
    reference_keys: set[tuple[str, DescriptorChannel]] = set()
    decoded: list[ReferenceFeatures] = []
    total_descriptors = 0
    for item in request.references:
        deadline.check()
        key = (item.reference_id, item.descriptor.channel)
        if key in reference_keys:
            raise InvalidDescriptorError(
                "reference ID and channel pairs must be unique within a snapshot"
            )
        reference_keys.add(key)
        descriptors, points = _validate_payload(item.descriptor)
        total_descriptors += descriptors.shape[0]
        if total_descriptors > MAX_TOTAL_DESCRIPTORS_PER_SNAPSHOT:
            raise CapacityError(
                f"a snapshot may contain at most {MAX_TOTAL_DESCRIPTORS_PER_SNAPSHOT} descriptors"
            )
        decoded.append(
            ReferenceFeatures(
                reference_id=item.reference_id,
                channel=item.descriptor.channel,
                source_sha256=item.descriptor.source_sha256,
                exclusion_mask_sha256=item.descriptor.exclusion_mask_sha256,
                descriptors=descriptors,
                points=points,
                working_width=item.descriptor.working_width,
                working_height=item.descriptor.working_height,
            )
        )

    digest = _snapshot_digest(decoded)
    channels: dict[DescriptorChannel, ChannelIndex] = {}
    for channel in ("BACKGROUND", "UNMASKED"):
        channel_references = [item for item in decoded if item.channel == channel]
        if channel_references:
            channels[channel] = _build_channel_index(
                channel, channel_references, deadline
            )
    deadline.check()
    return IndexSnapshot(request.revision, digest, channels)


class RevisionCache:
    def __init__(self) -> None:
        self._lock = threading.RLock()
        self._snapshots: OrderedDict[str, IndexSnapshot] = OrderedDict()

    @property
    def size(self) -> int:
        with self._lock:
            return len(self._snapshots)

    def get(self, revision: str) -> IndexSnapshot:
        with self._lock:
            snapshot = self._snapshots.get(revision)
            if snapshot is None:
                raise RevisionNotLoadedError()
            self._snapshots.move_to_end(revision)
            return snapshot

    def install(self, snapshot: IndexSnapshot) -> bool:
        with self._lock:
            existing = self._snapshots.get(snapshot.revision)
            if existing is not None:
                if existing.digest != snapshot.digest:
                    raise SnapshotConflictError()
                self._snapshots.move_to_end(snapshot.revision)
                return False
            self._snapshots[snapshot.revision] = snapshot
            while len(self._snapshots) > MAX_CACHED_REVISIONS:
                self._snapshots.popitem(last=False)
            return True


def _rerank_reference(
    query: ImageFeatures,
    reference: ReferenceFeatures,
    lsh_votes: int,
) -> RankedEvidence | None:
    matcher = cv2.BFMatcher(cv2.NORM_HAMMING, crossCheck=False)
    matches = matcher.knnMatch(query.descriptors, reference.descriptors, k=2)
    ratio_matches = [
        neighbors[0]
        for neighbors in matches
        if len(neighbors) == 2
        and neighbors[0].distance < LOWE_RATIO * neighbors[1].distance
    ]
    if len(ratio_matches) < MIN_RATIO_MATCHES:
        return None

    unique_train: dict[int, cv2.DMatch] = {}
    for match in ratio_matches:
        previous = unique_train.get(match.trainIdx)
        if previous is None or match.distance < previous.distance:
            unique_train[match.trainIdx] = match
    geometry_matches = list(unique_train.values())
    if len(geometry_matches) < MIN_RATIO_MATCHES:
        return None

    reference_points = np.asarray(
        [reference.points[match.trainIdx] for match in geometry_matches],
        dtype=np.float32,
    )
    query_points = np.asarray(
        [query.points[match.queryIdx] for match in geometry_matches],
        dtype=np.float32,
    )
    homography, mask = cv2.findHomography(
        reference_points,
        query_points,
        cv2.RANSAC,
        RANSAC_REPROJECTION_PIXELS,
        maxIters=RANSAC_MAX_ITERATIONS,
        confidence=RANSAC_CONFIDENCE,
    )
    if homography is None or mask is None or not np.isfinite(homography).all():
        return None
    inliers = int(np.count_nonzero(mask))
    inlier_ratio = inliers / len(geometry_matches)
    if inliers < MIN_HOMOGRAPHY_INLIERS or inlier_ratio < MIN_INLIER_RATIO:
        return None
    median_distance = float(np.median([match.distance for match in geometry_matches]))
    return RankedEvidence(
        reference_id=reference.reference_id,
        channel=reference.channel,
        lsh_votes=lsh_votes,
        ratio_matches=len(geometry_matches),
        homography_inliers=inliers,
        inlier_ratio=inlier_ratio,
        median_hamming_distance=median_distance,
    )


def query_snapshot(
    snapshot: IndexSnapshot,
    query: ImageFeatures,
    _top_k: int,
    deadline: Deadline,
) -> QueryResponse:
    if query.count < MIN_QUERY_DESCRIPTORS:
        return QueryResponse(
            status="INSUFFICIENT_FEATURES",
            complete=False,
            channel=query.channel,
            reference_revision=snapshot.revision,
            snapshot_digest=snapshot.digest,
            algorithm_version=ALGORITHM_VERSION,
            candidate_selection_version=CANDIDATE_SELECTION_VERSION,
            query_keypoint_count=query.count,
            distinctive_geometry=False,
            distinctive_inlier_lead=0,
            candidates=[],
        )

    deadline.check()
    channel_index = snapshot.channels.get(query.channel)
    shortlist = (
        channel_index.shortlist(query.descriptors) if channel_index is not None else []
    )
    evidence: list[RankedEvidence] = []
    for owner, votes in shortlist:
        deadline.check()
        ranked = _rerank_reference(query, channel_index.references[owner], votes)
        if ranked is not None:
            evidence.append(ranked)
    deadline.check()
    evidence.sort(
        key=lambda item: (
            -item.homography_inliers,
            -item.inlier_ratio,
            -item.ratio_matches,
            item.median_hamming_distance,
            item.reference_id,
        )
    )
    first_inliers = evidence[0].homography_inliers if evidence else 0
    second_inliers = evidence[1].homography_inliers if len(evidence) > 1 else 0
    lead = first_inliers - second_inliers
    distinctive = bool(evidence) and lead >= MIN_ORB_SPECIFICITY_INLIER_LEAD
    selected_evidence = evidence[:1] if distinctive else []
    candidates = [
        MatchCandidate(
            rank=rank,
            reference_id=item.reference_id,
            channel=item.channel,
            lsh_votes=item.lsh_votes,
            ratio_matches=item.ratio_matches,
            homography_inliers=item.homography_inliers,
            inlier_ratio=round(item.inlier_ratio, 6),
            median_hamming_distance=round(item.median_hamming_distance, 3),
        )
        for rank, item in enumerate(selected_evidence, start=1)
    ]
    return QueryResponse(
        status="OK" if candidates else "NO_GEOMETRIC_CANDIDATES",
        complete=True,
        channel=query.channel,
        reference_revision=snapshot.revision,
        snapshot_digest=snapshot.digest,
        algorithm_version=ALGORITHM_VERSION,
        candidate_selection_version=CANDIDATE_SELECTION_VERSION,
        query_keypoint_count=query.count,
        distinctive_geometry=distinctive,
        distinctive_inlier_lead=lead,
        candidates=candidates,
    )


def refresh_response(snapshot: IndexSnapshot, created: bool) -> RefreshResponse:
    return RefreshResponse(
        revision=snapshot.revision,
        snapshot_digest=snapshot.digest,
        reference_count=snapshot.reference_count,
        descriptor_count=snapshot.descriptor_count,
        created=created,
    )
