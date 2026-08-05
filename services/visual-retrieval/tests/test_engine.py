from __future__ import annotations

from types import SimpleNamespace

import numpy as np
import pytest

import app.index as index_module
from app.config import (
    ALGORITHM_VERSION,
    CANDIDATE_SELECTION_VERSION,
    MIN_ORB_SPECIFICITY_INLIER_LEAD,
)
from app.engine import VisualRetrievalEngine
from app.errors import RevisionNotLoadedError, SnapshotConflictError
from app.image_features import Deadline, ImageFeatures
from app.index import RankedEvidence, query_snapshot
from app.models import ReferenceDescriptor, RefreshRequest
from tests.helpers import rotate_image, synthetic_image


def _reference(
    engine: VisualRetrievalEngine,
    reference_id: str,
    image: bytes,
    channel: str = "UNMASKED",
    boxes: list[list[float]] | None = None,
) -> ReferenceDescriptor:
    payload = engine.compile(
        image,
        channel,  # type: ignore[arg-type]
        boxes or [],
        Deadline.after(3),
    )
    assert payload.usable
    return ReferenceDescriptor(reference_id=reference_id, descriptor=payload)


class _StubChannelIndex:
    def __init__(self, reference_ids: list[str]) -> None:
        self.references = tuple(reference_ids)

    def shortlist(self, _query_descriptors: np.ndarray) -> list[tuple[int, int]]:
        return [(owner, 100 - owner) for owner in range(len(self.references))]


def _specificity_response(
    monkeypatch: pytest.MonkeyPatch,
    inliers: list[int],
    *,
    top_k: int,
):
    reference_ids = [f"ref-{index}" for index in range(len(inliers))]
    evidence_by_reference = {
        reference_id: RankedEvidence(
            reference_id=reference_id,
            channel="UNMASKED",
            lsh_votes=100 - index,
            ratio_matches=inlier_count + 4,
            homography_inliers=inlier_count,
            inlier_ratio=inlier_count / (inlier_count + 4),
            median_hamming_distance=20.0 + index,
        )
        for index, (reference_id, inlier_count) in enumerate(
            zip(reference_ids, inliers, strict=True)
        )
    }
    monkeypatch.setattr(
        index_module,
        "_rerank_reference",
        lambda _query, reference_id, _votes: evidence_by_reference[reference_id],
    )
    snapshot = SimpleNamespace(
        revision="specificity-r1",
        digest="0" * 64,
        channels={"UNMASKED": _StubChannelIndex(reference_ids)},
    )
    query = ImageFeatures(
        channel="UNMASKED",
        descriptors=np.zeros((16, 32), dtype=np.uint8),
        points=np.zeros((16, 2), dtype=np.float32),
        working_width=100,
        working_height=100,
        exclusion_mask_sha256=None,
    )
    return query_snapshot(snapshot, query, top_k, Deadline.after(3))


def test_lsh_and_homography_rank_transformed_reference_first() -> None:
    engine = VisualRetrievalEngine()
    intended = synthetic_image(101)
    unrelated = synthetic_image(202)
    request = RefreshRequest(
        revision="catalog-1",
        references=[
            _reference(engine, "intended", intended),
            _reference(engine, "unrelated", unrelated),
        ],
    )
    refreshed = engine.refresh(request, Deadline.after(10))
    result = engine.query(
        rotate_image(intended),
        "catalog-1",
        5,
        "UNMASKED",
        [],
        Deadline.after(5),
    )

    assert refreshed.reference_count == 2
    assert result.candidate_only is True
    assert result.authoritative is False
    assert result.status == "OK"
    assert result.candidates[0].reference_id == "intended"
    assert result.candidates[0].channel == "UNMASKED"
    assert result.candidates[0].homography_inliers >= 6
    assert len(result.candidates) <= 5


def test_specificity_margin_emits_exactly_the_rank_one_winner(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    result = _specificity_response(
        monkeypatch,
        [40, 28, 17],
        top_k=5,
    )

    assert result.status == "OK"
    assert result.complete is True
    assert result.candidate_only is True
    assert result.authoritative is False
    assert result.candidate_selection_version == CANDIDATE_SELECTION_VERSION
    assert result.distinctive_geometry is True
    assert result.distinctive_inlier_lead == MIN_ORB_SPECIFICITY_INLIER_LEAD
    assert [(candidate.rank, candidate.reference_id) for candidate in result.candidates] == [
        (1, "ref-0")
    ]


def test_ambiguous_runner_up_abstains_even_when_top_k_is_one(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    result = _specificity_response(
        monkeypatch,
        [40, 29],
        top_k=1,
    )

    assert result.status == "NO_GEOMETRIC_CANDIDATES"
    assert result.complete is True
    assert result.candidate_only is True
    assert result.authoritative is False
    assert result.candidate_selection_version == CANDIDATE_SELECTION_VERSION
    assert result.distinctive_geometry is False
    assert result.distinctive_inlier_lead == MIN_ORB_SPECIFICITY_INLIER_LEAD - 1
    assert result.candidates == []


def test_single_reference_below_margin_abstains_with_zero_runner_up(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    result = _specificity_response(monkeypatch, [11], top_k=5)

    assert result.status == "NO_GEOMETRIC_CANDIDATES"
    assert result.distinctive_geometry is False
    assert result.distinctive_inlier_lead == 11
    assert result.candidates == []


def test_single_reference_at_margin_emits_with_zero_runner_up(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    result = _specificity_response(monkeypatch, [12], top_k=5)

    assert result.status == "OK"
    assert result.distinctive_geometry is True
    assert result.distinctive_inlier_lead == 12
    assert [(candidate.rank, candidate.reference_id) for candidate in result.candidates] == [
        (1, "ref-0")
    ]


def test_channels_are_indexed_and_compared_separately() -> None:
    engine = VisualRetrievalEngine()
    source = synthetic_image(303)
    boxes = [[140 / 719, 165 / 479, 440 / 719, 150 / 479]]
    request = RefreshRequest(
        revision="catalog-channels",
        references=[
            _reference(engine, "same-ref", source, "UNMASKED"),
            _reference(engine, "same-ref", source, "BACKGROUND", boxes),
        ],
    )
    engine.refresh(request, Deadline.after(10))

    background = engine.query(
        source,
        "catalog-channels",
        5,
        "BACKGROUND",
        boxes,
        Deadline.after(5),
    )
    unmasked = engine.query(
        source,
        "catalog-channels",
        5,
        "UNMASKED",
        [],
        Deadline.after(5),
    )

    assert background.candidates[0].channel == "BACKGROUND"
    assert unmasked.candidates[0].channel == "UNMASKED"
    assert all(candidate.channel == background.channel for candidate in background.candidates)
    assert all(candidate.channel == unmasked.channel for candidate in unmasked.candidates)


def test_empty_snapshot_is_ready_and_queryable() -> None:
    engine = VisualRetrievalEngine()
    refreshed = engine.refresh(
        RefreshRequest(revision="empty-1", references=[]), Deadline.after(3)
    )
    result = engine.query(
        synthetic_image(404),
        "empty-1",
        5,
        "UNMASKED",
        [],
        Deadline.after(5),
    )

    assert refreshed.reference_count == 0
    assert refreshed.descriptor_count == 0
    assert result.status == "NO_GEOMETRIC_CANDIDATES"
    assert result.complete is True
    assert result.candidates == []


def test_revision_is_idempotent_but_immutable() -> None:
    engine = VisualRetrievalEngine()
    first = RefreshRequest(
        revision="immutable-1",
        references=[_reference(engine, "ref", synthetic_image(501))],
    )
    assert engine.refresh(first, Deadline.after(10)).created is True
    assert engine.refresh(first, Deadline.after(10)).created is False

    changed = RefreshRequest(
        revision="immutable-1",
        references=[_reference(engine, "ref", synthetic_image(502))],
    )
    with pytest.raises(SnapshotConflictError):
        engine.refresh(changed, Deadline.after(10))


def test_query_requires_exact_loaded_revision() -> None:
    engine = VisualRetrievalEngine()
    with pytest.raises(RevisionNotLoadedError):
        engine.query(
            synthetic_image(601),
            "missing-revision",
            5,
            "UNMASKED",
            [],
            Deadline.after(5),
        )


def test_algorithm_version_constant_is_explicit() -> None:
    assert ALGORITHM_VERSION == "opencv-orb-4.12-v1"
    assert CANDIDATE_SELECTION_VERSION == "orb-homography-specificity-v1"
    assert MIN_ORB_SPECIFICITY_INLIER_LEAD == 12
