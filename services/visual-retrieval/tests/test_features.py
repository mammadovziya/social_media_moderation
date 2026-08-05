from __future__ import annotations

import base64
import hashlib
import io

from PIL import Image
import pytest

from app.config import DESCRIPTOR_BYTES, EXCLUSION_MASK_VERSION, ORB_MAX_FEATURES
from app.errors import InvalidImageError
from app.image_features import Deadline, extract_features, payload_from_features
from tests.helpers import synthetic_image


def test_compile_payload_is_bounded_normalized_and_content_bound() -> None:
    source = synthetic_image(11)
    features = extract_features(source, Deadline.after(3), "UNMASKED", [])
    payload = payload_from_features(features, hashlib.sha256(source).hexdigest())

    assert 0 < payload.keypoint_count <= ORB_MAX_FEATURES
    assert payload.keypoint_count == len(payload.keypoints)
    assert all(len(point) == 2 for point in payload.keypoints)
    assert all(0.0 <= value <= 1.0 for point in payload.keypoints for value in point)
    assert len(base64.b64decode(payload.descriptors_base64)) == (
        payload.keypoint_count * DESCRIPTOR_BYTES
    )
    assert payload.source_sha256 == hashlib.sha256(source).hexdigest()
    assert payload.channel == "UNMASKED"
    assert payload.exclusion_mask_sha256 is None
    assert payload.exclusion_mask_version is None


def test_compilation_is_deterministic_for_pinned_profile() -> None:
    source = synthetic_image(111)
    first = payload_from_features(
        extract_features(source, Deadline.after(3), "UNMASKED", []),
        hashlib.sha256(source).hexdigest(),
    )
    second = payload_from_features(
        extract_features(source, Deadline.after(3), "UNMASKED", []),
        hashlib.sha256(source).hexdigest(),
    )

    assert first.model_dump() == second.model_dump()


def test_background_channel_excludes_padded_text_region() -> None:
    source = synthetic_image(12)
    box = [[140 / 719, 165 / 479, 440 / 719, 150 / 479]]
    features = extract_features(source, Deadline.after(3), "BACKGROUND", box)
    payload = payload_from_features(features, hashlib.sha256(source).hexdigest())

    assert payload.usable
    assert payload.channel == "BACKGROUND"
    assert payload.exclusion_mask_version == EXCLUSION_MASK_VERSION
    assert payload.exclusion_mask_sha256 is not None
    assert all(
        not (
            box[0][0] <= point[0] <= box[0][0] + box[0][2]
            and box[0][1] <= point[1] <= box[0][1] + box[0][3]
        )
        for point in payload.keypoints
    )


def test_channel_and_exclusion_box_contract_is_strict() -> None:
    source = synthetic_image(13)
    with pytest.raises(InvalidImageError, match="requires exclusion boxes"):
        extract_features(source, Deadline.after(3), "BACKGROUND", [])
    with pytest.raises(InvalidImageError, match="must not include"):
        extract_features(
            source,
            Deadline.after(3),
            "UNMASKED",
            [[0.1, 0.1, 0.2, 0.2]],
        )
    with pytest.raises(InvalidImageError, match="positive width"):
        extract_features(
            source,
            Deadline.after(3),
            "BACKGROUND",
            [[0.4, 0.1, 0.0, 0.3]],
        )


def test_featureless_image_is_explicitly_unusable() -> None:
    image = Image.new("RGB", (320, 240), "white")
    output = io.BytesIO()
    image.save(output, format="PNG")
    source = output.getvalue()
    features = extract_features(source, Deadline.after(3), "UNMASKED", [])
    payload = payload_from_features(features, hashlib.sha256(source).hexdigest())

    assert payload.keypoint_count == 0
    assert payload.usable is False


def test_static_gif_is_supported_but_animated_gif_is_rejected() -> None:
    source_image = Image.open(io.BytesIO(synthetic_image(14))).convert("P")
    static_output = io.BytesIO()
    source_image.save(static_output, format="GIF")
    static_features = extract_features(
        static_output.getvalue(), Deadline.after(3), "UNMASKED", []
    )
    assert static_features.count > 0

    animated_output = io.BytesIO()
    second_frame = Image.new("P", source_image.size, color=1)
    source_image.save(
        animated_output,
        format="GIF",
        save_all=True,
        append_images=[second_frame],
        duration=100,
        loop=0,
    )
    with pytest.raises(InvalidImageError, match="multi-frame"):
        extract_features(
            animated_output.getvalue(), Deadline.after(3), "UNMASKED", []
        )
