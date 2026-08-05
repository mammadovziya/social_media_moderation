from __future__ import annotations

import os
import re
from dataclasses import dataclass


SERVICE_VERSION = "1.1.0"
DESCRIPTOR_SCHEMA_VERSION = "orb-descriptor-payload/v1"
ALGORITHM_VERSION = "opencv-orb-4.12-v1"
CANDIDATE_SELECTION_VERSION = "orb-homography-specificity-v1"
CANONICALIZATION_VERSION = "pillow-exif-rgba-white-gray-cv-area/v1"
EXCLUSION_MASK_VERSION = "normalized-box-padding-64px/v1"

ORB_MAX_FEATURES = 1_800
ORB_SCALE_FACTOR = 1.2
ORB_LEVELS = 8
ORB_EDGE_THRESHOLD = 31
ORB_PATCH_SIZE = 31
ORB_FAST_THRESHOLD = 20
DESCRIPTOR_BYTES = 32
MAX_DESCRIPTOR_BASE64_LENGTH = ((ORB_MAX_FEATURES * DESCRIPTOR_BYTES + 2) // 3) * 4
EXCLUSION_PADDING_PIXELS = 64
MAX_EXCLUSION_BOXES = 256
MAX_EXCLUDED_FRACTION = 0.80

LOWE_RATIO = 0.72
RANSAC_REPROJECTION_PIXELS = 4.0
RANSAC_MAX_ITERATIONS = 1_000
RANSAC_CONFIDENCE = 0.995

LSH_TABLE_NUMBER = 12
LSH_KEY_SIZE = 20
LSH_MULTI_PROBE_LEVEL = 2
LSH_MAX_HAMMING_DISTANCE = 96
MAX_RERANK_CANDIDATES = 20

MIN_REFERENCE_DESCRIPTORS = 16
MIN_QUERY_DESCRIPTORS = 16
MIN_RATIO_MATCHES = 8
MIN_HOMOGRAPHY_INLIERS = 6
MIN_INLIER_RATIO = 0.25
# Candidate-emission behavior is governed separately by
# CANDIDATE_SELECTION_VERSION. A valid ORB match is returned only when its
# homography support is sufficiently more specific than every other valid,
# geometrically verified match in the bounded LSH shortlist.
MIN_ORB_SPECIFICITY_INLIER_LEAD = 12

MAX_IMAGE_BYTES = 8 * 1024 * 1024
MAX_IMAGE_REQUEST_BYTES = 9 * 1024 * 1024
MAX_REFRESH_REQUEST_BYTES = 64 * 1024 * 1024
MAX_SOURCE_PIXELS = 16_777_216
MAX_SOURCE_DIMENSION = 8_192
MIN_SOURCE_DIMENSION = 32
MAX_WORKING_DIMENSION = 2_048

MAX_REFERENCES_PER_SNAPSHOT = 256
MAX_TOTAL_DESCRIPTORS_PER_SNAPSHOT = 250_000
MAX_CACHED_REVISIONS = 3
MAX_TOP_K = 5
MAX_REVISION_LENGTH = 128
MAX_REFERENCE_ID_LENGTH = 128

COMPILE_TIMEOUT_SECONDS = 10.0
QUERY_TIMEOUT_SECONDS = 10.0
REFRESH_TIMEOUT_SECONDS = 25.0
MAX_CONCURRENT_CPU_JOBS = 2


@dataclass(frozen=True, slots=True)
class Settings:
    """Security settings; algorithm and capacity limits are versioned constants."""

    internal_token: str | None = None
    allow_unauthenticated: bool = False

    @classmethod
    def from_env(cls) -> "Settings":
        raw_token = os.getenv("VISUAL_RETRIEVAL_INTERNAL_TOKEN")
        token = raw_token.strip() if raw_token and raw_token.strip() else None
        allow = os.getenv("VISUAL_RETRIEVAL_ALLOW_UNAUTHENTICATED", "false").lower()
        if allow not in {"true", "false"}:
            raise RuntimeError(
                "VISUAL_RETRIEVAL_ALLOW_UNAUTHENTICATED must be true or false"
            )
        if token is not None and (
            not 32 <= len(token) <= 512
            or re.fullmatch(r"[A-Za-z0-9._~-]+", token) is None
        ):
            raise RuntimeError(
                "VISUAL_RETRIEVAL_INTERNAL_TOKEN must contain 32 to 512 URL-safe characters"
            )
        return cls(internal_token=token, allow_unauthenticated=allow == "true")

    @property
    def authentication_ready(self) -> bool:
        return self.allow_unauthenticated or self.internal_token is not None
