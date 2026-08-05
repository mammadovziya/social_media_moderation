from __future__ import annotations

import hashlib

import cv2

from .image_features import Deadline, extract_features, payload_from_features
from .index import RevisionCache, build_snapshot, query_snapshot, refresh_response
from .models import (
    DescriptorChannel,
    DescriptorPayload,
    QueryResponse,
    RefreshRequest,
    RefreshResponse,
)


class VisualRetrievalEngine:
    def __init__(self) -> None:
        cv2.setNumThreads(1)
        cv2.ocl.setUseOpenCL(False)
        self.cache = RevisionCache()

    def compile(
        self,
        image_bytes: bytes,
        channel: DescriptorChannel,
        exclusion_boxes: list[list[float]],
        deadline: Deadline,
    ) -> DescriptorPayload:
        source_sha256 = hashlib.sha256(image_bytes).hexdigest()
        features = extract_features(image_bytes, deadline, channel, exclusion_boxes)
        return payload_from_features(features, source_sha256)

    def refresh(
        self, request: RefreshRequest, deadline: Deadline
    ) -> RefreshResponse:
        snapshot = build_snapshot(request, deadline)
        created = self.cache.install(snapshot)
        return refresh_response(snapshot, created)

    def query(
        self,
        image_bytes: bytes,
        revision: str,
        top_k: int,
        channel: DescriptorChannel,
        exclusion_boxes: list[list[float]],
        deadline: Deadline,
    ) -> QueryResponse:
        snapshot = self.cache.get(revision)
        features = extract_features(image_bytes, deadline, channel, exclusion_boxes)
        return query_snapshot(snapshot, features, top_k, deadline)
