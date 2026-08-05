from __future__ import annotations

import base64
import hashlib
import io
import math
import time
import warnings
from dataclasses import dataclass

import cv2
import numpy as np
from PIL import Image, ImageOps, UnidentifiedImageError

from .config import (
    ALGORITHM_VERSION,
    CANONICALIZATION_VERSION,
    DESCRIPTOR_BYTES,
    DESCRIPTOR_SCHEMA_VERSION,
    EXCLUSION_MASK_VERSION,
    EXCLUSION_PADDING_PIXELS,
    MAX_EXCLUDED_FRACTION,
    MAX_EXCLUSION_BOXES,
    MAX_SOURCE_DIMENSION,
    MAX_SOURCE_PIXELS,
    MAX_WORKING_DIMENSION,
    MIN_REFERENCE_DESCRIPTORS,
    MIN_SOURCE_DIMENSION,
    ORB_EDGE_THRESHOLD,
    ORB_FAST_THRESHOLD,
    ORB_LEVELS,
    ORB_MAX_FEATURES,
    ORB_PATCH_SIZE,
    ORB_SCALE_FACTOR,
)
from .errors import InvalidImageError, ProcessingTimeoutError
from .models import AlgorithmMetadata, DescriptorChannel, DescriptorPayload


ALLOWED_IMAGE_FORMATS = frozenset({"JPEG", "PNG", "GIF", "WEBP"})


@dataclass(frozen=True, slots=True)
class Deadline:
    expires_at: float

    @classmethod
    def after(cls, seconds: float) -> "Deadline":
        return cls(time.monotonic() + seconds)

    def check(self) -> None:
        if time.monotonic() >= self.expires_at:
            raise ProcessingTimeoutError()

    @property
    def remaining(self) -> float:
        return max(0.0, self.expires_at - time.monotonic())


@dataclass(frozen=True, slots=True)
class ImageFeatures:
    channel: DescriptorChannel
    descriptors: np.ndarray
    points: np.ndarray
    working_width: int
    working_height: int
    exclusion_mask_sha256: str | None

    @property
    def count(self) -> int:
        return int(self.descriptors.shape[0])


def algorithm_metadata() -> AlgorithmMetadata:
    return AlgorithmMetadata(
        algorithm_version=ALGORITHM_VERSION,
        implementation_version=cv2.__version__,
        canonicalization_version=CANONICALIZATION_VERSION,
        descriptor_bytes=DESCRIPTOR_BYTES,
        max_features=ORB_MAX_FEATURES,
    )


def _validate_dimensions(width: int, height: int) -> None:
    if width < MIN_SOURCE_DIMENSION or height < MIN_SOURCE_DIMENSION:
        raise InvalidImageError(
            f"image dimensions must each be at least {MIN_SOURCE_DIMENSION} pixels"
        )
    if width > MAX_SOURCE_DIMENSION or height > MAX_SOURCE_DIMENSION:
        raise InvalidImageError(
            f"image dimensions must not exceed {MAX_SOURCE_DIMENSION} pixels"
        )
    if width * height > MAX_SOURCE_PIXELS:
        raise InvalidImageError(
            f"decoded image must not exceed {MAX_SOURCE_PIXELS} pixels"
        )


def _decode_canonical_grayscale(image_bytes: bytes, deadline: Deadline) -> np.ndarray:
    deadline.check()
    Image.MAX_IMAGE_PIXELS = MAX_SOURCE_PIXELS
    try:
        with warnings.catch_warnings():
            warnings.simplefilter("error", Image.DecompressionBombWarning)
            with Image.open(io.BytesIO(image_bytes)) as probe:
                if probe.format not in ALLOWED_IMAGE_FORMATS:
                    raise InvalidImageError(
                        "only static JPEG, PNG, GIF, and WebP images are accepted"
                    )
                if getattr(probe, "n_frames", 1) != 1:
                    raise InvalidImageError("animated or multi-frame images are not accepted")
                _validate_dimensions(*probe.size)
                probe.verify()

            deadline.check()
            with Image.open(io.BytesIO(image_bytes)) as decoded:
                if decoded.format not in ALLOWED_IMAGE_FORMATS:
                    raise InvalidImageError("image format changed during verification")
                decoded = ImageOps.exif_transpose(decoded)
                _validate_dimensions(*decoded.size)
                decoded.load()

                if "A" in decoded.getbands() or "transparency" in decoded.info:
                    rgba = decoded.convert("RGBA")
                    background = Image.new("RGBA", rgba.size, (255, 255, 255, 255))
                    grayscale_image = Image.alpha_composite(background, rgba).convert("L")
                else:
                    grayscale_image = decoded.convert("L")
                grayscale = np.asarray(grayscale_image, dtype=np.uint8).copy()
    except InvalidImageError:
        raise
    except (Image.DecompressionBombError, Image.DecompressionBombWarning) as exc:
        raise InvalidImageError("image exceeds the decoded pixel limit") from exc
    except (UnidentifiedImageError, OSError, SyntaxError, ValueError) as exc:
        raise InvalidImageError("image bytes could not be decoded safely") from exc

    deadline.check()
    height, width = grayscale.shape
    largest = max(width, height)
    if largest > MAX_WORKING_DIMENSION:
        scale = MAX_WORKING_DIMENSION / largest
        target = (
            max(MIN_SOURCE_DIMENSION, int(round(width * scale))),
            max(MIN_SOURCE_DIMENSION, int(round(height * scale))),
        )
        grayscale = cv2.resize(grayscale, target, interpolation=cv2.INTER_AREA)
    return np.ascontiguousarray(grayscale, dtype=np.uint8)


def validate_exclusion_boxes(
    channel: DescriptorChannel,
    exclusion_boxes: list[list[float]],
) -> list[tuple[float, float, float, float]]:
    if len(exclusion_boxes) > MAX_EXCLUSION_BOXES:
        raise InvalidImageError(
            f"at most {MAX_EXCLUSION_BOXES} exclusion boxes are accepted"
        )
    if channel == "BACKGROUND" and not exclusion_boxes:
        raise InvalidImageError("BACKGROUND extraction requires exclusion boxes")
    if channel == "UNMASKED" and exclusion_boxes:
        raise InvalidImageError("UNMASKED extraction must not include exclusion boxes")
    validated: list[tuple[float, float, float, float]] = []
    for box in exclusion_boxes:
        if len(box) != 4:
            raise InvalidImageError(
                "each exclusion box must be [x, y, width, height]"
            )
        if any(isinstance(value, bool) or not isinstance(value, (int, float)) for value in box):
            raise InvalidImageError("exclusion box coordinates must be numbers")
        coordinates = tuple(float(value) for value in box)
        if not all(math.isfinite(value) and 0.0 <= value <= 1.0 for value in coordinates):
            raise InvalidImageError("exclusion box coordinates must be finite and normalized")
        x, y, box_width, box_height = coordinates
        if box_width <= 0.0 or box_height <= 0.0:
            raise InvalidImageError("exclusion boxes must have positive width and height")
        if x + box_width > 1.0 or y + box_height > 1.0:
            raise InvalidImageError("exclusion boxes must fit inside normalized image bounds")
        validated.append((x, y, box_width, box_height))
    return validated


def _exclusion_mask(
    shape: tuple[int, int],
    boxes: list[tuple[float, float, float, float]],
) -> tuple[np.ndarray | None, str | None]:
    if not boxes:
        return None, None
    height, width = shape
    mask = np.full((height, width), 255, dtype=np.uint8)
    for x, y, box_width, box_height in boxes:
        left = max(0, math.floor(x * (width - 1)) - EXCLUSION_PADDING_PIXELS)
        top = max(0, math.floor(y * (height - 1)) - EXCLUSION_PADDING_PIXELS)
        right = min(
            width,
            math.ceil((x + box_width) * (width - 1))
            + EXCLUSION_PADDING_PIXELS
            + 1,
        )
        bottom = min(
            height,
            math.ceil((y + box_height) * (height - 1))
            + EXCLUSION_PADDING_PIXELS
            + 1,
        )
        mask[top:bottom, left:right] = 0
    excluded_fraction = 1.0 - (float(np.count_nonzero(mask)) / mask.size)
    if excluded_fraction > MAX_EXCLUDED_FRACTION:
        raise InvalidImageError(
            f"exclusion boxes may cover at most {MAX_EXCLUDED_FRACTION:.0%} of the working image"
        )
    mask.setflags(write=False)
    return mask, hashlib.sha256(mask.tobytes(order="C")).hexdigest()


def extract_features(
    image_bytes: bytes,
    deadline: Deadline,
    channel: DescriptorChannel,
    exclusion_boxes: list[list[float]],
) -> ImageFeatures:
    boxes = validate_exclusion_boxes(channel, exclusion_boxes)
    grayscale = _decode_canonical_grayscale(image_bytes, deadline)
    deadline.check()
    mask, mask_sha256 = _exclusion_mask(grayscale.shape, boxes)
    orb = cv2.ORB_create(
        nfeatures=ORB_MAX_FEATURES,
        scaleFactor=ORB_SCALE_FACTOR,
        nlevels=ORB_LEVELS,
        edgeThreshold=ORB_EDGE_THRESHOLD,
        firstLevel=0,
        WTA_K=2,
        scoreType=cv2.ORB_HARRIS_SCORE,
        patchSize=ORB_PATCH_SIZE,
        fastThreshold=ORB_FAST_THRESHOLD,
    )
    keypoints, descriptors = orb.detectAndCompute(grayscale, mask)
    deadline.check()

    height, width = grayscale.shape
    if descriptors is None or not keypoints:
        descriptor_array = np.empty((0, DESCRIPTOR_BYTES), dtype=np.uint8)
        points = np.empty((0, 2), dtype=np.float32)
    else:
        descriptor_array = np.ascontiguousarray(descriptors, dtype=np.uint8)
        if descriptor_array.ndim != 2 or descriptor_array.shape[1] != DESCRIPTOR_BYTES:
            raise InvalidImageError("ORB returned an unexpected descriptor shape")
        if descriptor_array.shape[0] > ORB_MAX_FEATURES:
            descriptor_array = np.ascontiguousarray(
                descriptor_array[:ORB_MAX_FEATURES], dtype=np.uint8
            )
            keypoints = keypoints[:ORB_MAX_FEATURES]
        points = np.asarray([keypoint.pt for keypoint in keypoints], dtype=np.float32)
        if points.shape != (descriptor_array.shape[0], 2):
            raise InvalidImageError("ORB keypoint and descriptor counts differ")

    descriptor_array.setflags(write=False)
    points.setflags(write=False)
    return ImageFeatures(
        channel=channel,
        descriptors=descriptor_array,
        points=points,
        working_width=width,
        working_height=height,
        exclusion_mask_sha256=mask_sha256,
    )


def payload_from_features(
    features: ImageFeatures, source_sha256: str
) -> DescriptorPayload:
    descriptor_bytes = features.descriptors.tobytes(order="C")
    width_denominator = max(1, features.working_width - 1)
    height_denominator = max(1, features.working_height - 1)
    normalized_points = [
        [
            round(float(point[0]) / width_denominator, 8),
            round(float(point[1]) / height_denominator, 8),
        ]
        for point in features.points
    ]
    if not all(
        math.isfinite(coordinate)
        for point in normalized_points
        for coordinate in point
    ):
        raise InvalidImageError("ORB returned a non-finite keypoint")
    return DescriptorPayload(
        schema_version=DESCRIPTOR_SCHEMA_VERSION,
        channel=features.channel,
        algorithm=algorithm_metadata(),
        working_width=features.working_width,
        working_height=features.working_height,
        keypoint_count=features.count,
        keypoints=normalized_points,
        descriptors_base64=base64.b64encode(descriptor_bytes).decode("ascii"),
        source_sha256=source_sha256,
        descriptor_sha256=hashlib.sha256(descriptor_bytes).hexdigest(),
        exclusion_mask_version=(
            EXCLUSION_MASK_VERSION if features.channel == "BACKGROUND" else None
        ),
        exclusion_mask_sha256=features.exclusion_mask_sha256,
        usable=features.count >= MIN_REFERENCE_DESCRIPTORS,
    )
