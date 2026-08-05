from __future__ import annotations

from typing import Annotated, Literal

from pydantic import (
    BaseModel,
    ConfigDict,
    Field,
    StringConstraints,
    field_validator,
)

from .config import (
    MAX_DESCRIPTOR_BASE64_LENGTH,
    MAX_REFERENCE_ID_LENGTH,
    MAX_REFERENCES_PER_SNAPSHOT,
    MAX_REVISION_LENGTH,
    ORB_MAX_FEATURES,
)


StrictModelConfig = ConfigDict(
    alias_generator=lambda value: "".join(
        [value.split("_")[0], *[part.title() for part in value.split("_")[1:]]]
    ),
    populate_by_name=True,
    extra="forbid",
    strict=True,
)

Revision = Annotated[
    str,
    StringConstraints(
        min_length=1,
        max_length=MAX_REVISION_LENGTH,
        pattern=r"^[A-Za-z0-9][A-Za-z0-9._:-]*$",
    ),
]
ReferenceId = Annotated[
    str,
    StringConstraints(
        min_length=1,
        max_length=MAX_REFERENCE_ID_LENGTH,
        pattern=r"^[A-Za-z0-9][A-Za-z0-9._:@/-]*$",
    ),
]
DescriptorChannel = Literal["BACKGROUND", "UNMASKED"]


class AlgorithmMetadata(BaseModel):
    model_config = StrictModelConfig

    name: Literal["ORB"] = "ORB"
    algorithm_version: str
    implementation: Literal["OpenCV"] = "OpenCV"
    implementation_version: str
    canonicalization_version: str
    descriptor_type: Literal["binary-uint8"] = "binary-uint8"
    descriptor_bytes: int
    max_features: int


class DescriptorPayload(BaseModel):
    model_config = StrictModelConfig

    schema_version: str
    channel: DescriptorChannel
    algorithm: AlgorithmMetadata
    working_width: int = Field(ge=1)
    working_height: int = Field(ge=1)
    keypoint_count: int = Field(ge=0)
    keypoints: list[list[float]] = Field(max_length=ORB_MAX_FEATURES)
    descriptors_base64: str = Field(max_length=MAX_DESCRIPTOR_BASE64_LENGTH)
    source_sha256: str = Field(pattern=r"^[0-9a-f]{64}$")
    descriptor_sha256: str = Field(pattern=r"^[0-9a-f]{64}$")
    exclusion_mask_version: str | None
    exclusion_mask_sha256: str | None = Field(pattern=r"^[0-9a-f]{64}$")
    usable: bool

    @field_validator("keypoints")
    @classmethod
    def validate_normalized_keypoints(
        cls, keypoints: list[list[float]]
    ) -> list[list[float]]:
        for point in keypoints:
            if len(point) != 2:
                raise ValueError("each keypoint must have exactly two coordinates")
            if not all(0.0 <= coordinate <= 1.0 for coordinate in point):
                raise ValueError("keypoint coordinates must be normalized to [0, 1]")
        return keypoints


class ReferenceDescriptor(BaseModel):
    model_config = StrictModelConfig

    reference_id: ReferenceId
    descriptor: DescriptorPayload


class RefreshRequest(BaseModel):
    model_config = StrictModelConfig

    revision: Revision
    references: list[ReferenceDescriptor] = Field(
        max_length=MAX_REFERENCES_PER_SNAPSHOT
    )


class RefreshResponse(BaseModel):
    model_config = StrictModelConfig

    revision: Revision
    snapshot_digest: str
    reference_count: int
    descriptor_count: int
    created: bool


class MatchCandidate(BaseModel):
    model_config = StrictModelConfig

    rank: int
    reference_id: ReferenceId
    channel: DescriptorChannel
    lsh_votes: int
    ratio_matches: int
    homography_inliers: int
    inlier_ratio: float
    median_hamming_distance: float


class QueryResponse(BaseModel):
    model_config = StrictModelConfig

    status: Literal["OK", "INSUFFICIENT_FEATURES", "NO_GEOMETRIC_CANDIDATES"]
    complete: bool
    candidate_only: Literal[True] = True
    authoritative: Literal[False] = False
    channel: DescriptorChannel
    reference_revision: Revision
    snapshot_digest: str
    algorithm_version: str
    candidate_selection_version: str
    query_keypoint_count: int
    distinctive_geometry: bool
    distinctive_inlier_lead: int
    candidates: list[MatchCandidate]
