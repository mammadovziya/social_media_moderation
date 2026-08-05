from __future__ import annotations

from fastapi.testclient import TestClient

from app.api import create_app
from app.config import ALGORITHM_VERSION, CANDIDATE_SELECTION_VERSION, Settings
from tests.helpers import synthetic_image


TOKEN = "test-internal-token-that-is-at-least-32-characters"


def _client() -> TestClient:
    return TestClient(create_app(Settings(internal_token=TOKEN)))


def _compile(client: TestClient, image: bytes, channel: str = "UNMASKED") -> dict:
    boxes = "[]" if channel == "UNMASKED" else "[[0.2,0.3,0.6,0.4]]"
    response = client.post(
        "/internal/v1/descriptors/compile",
        headers={"X-Internal-Token": TOKEN},
        data={
            "descriptorVersion": ALGORITHM_VERSION,
            "channel": channel,
            "exclusionBoxes": boxes,
        },
        files={"image": ("ignored.png", image, "image/png")},
    )
    assert response.status_code == 200, response.text
    return response.json()


def test_health_readiness_and_authentication() -> None:
    client = _client()
    health = client.get("/health")
    assert health.status_code == 200
    assert health.json()["version"] == "1.1.0"
    ready = client.get("/ready")
    assert ready.status_code == 200
    assert ready.json()["loadedRevisions"] == 0
    assert ready.json()["candidateSelectionVersion"] == CANDIDATE_SELECTION_VERSION

    unauthorized = client.post(
        "/internal/v1/indexes/refresh",
        json={"revision": "r1", "references": []},
    )
    assert unauthorized.status_code == 401
    assert unauthorized.json()["error"]["code"] == "unauthorized"


def test_configured_token_is_mandatory_even_in_local_unauthenticated_mode() -> None:
    client = TestClient(
        create_app(Settings(internal_token=TOKEN, allow_unauthenticated=True))
    )

    unauthorized = client.post(
        "/internal/v1/indexes/refresh",
        json={"revision": "empty", "references": []},
    )
    authorized = client.post(
        "/internal/v1/indexes/refresh",
        headers={"X-Internal-Token": TOKEN},
        json={"revision": "empty", "references": []},
    )

    assert unauthorized.status_code == 401
    assert unauthorized.json()["error"]["code"] == "unauthorized"
    assert authorized.status_code == 200


def test_compile_refresh_and_candidate_only_query_contract() -> None:
    client = _client()
    source = synthetic_image(701)
    descriptor = _compile(client, source)
    assert descriptor["channel"] == "UNMASKED"
    assert len(descriptor["sourceSha256"]) == 64
    assert len(descriptor["descriptorSha256"]) == 64

    refresh = client.post(
        "/internal/v1/indexes/refresh",
        headers={"X-Internal-Token": TOKEN},
        json={
            "revision": "api-r1",
            "references": [{"referenceId": "ref-701", "descriptor": descriptor}],
        },
    )
    assert refresh.status_code == 200, refresh.text

    query = client.post(
        "/internal/v1/query",
        headers={"X-Internal-Token": TOKEN},
        data={
            "revision": "api-r1",
            "topK": "5",
            "descriptorVersion": ALGORITHM_VERSION,
            "channel": "UNMASKED",
            "exclusionBoxes": "[]",
        },
        files={"image": ("ignored.png", source, "image/png")},
    )
    assert query.status_code == 200, query.text
    body = query.json()
    assert body["candidateOnly"] is True
    assert body["authoritative"] is False
    assert body["candidateSelectionVersion"] == CANDIDATE_SELECTION_VERSION
    assert body["channel"] == "UNMASKED"
    assert body["candidates"][0]["referenceId"] == "ref-701"
    assert body["candidates"][0]["channel"] == "UNMASKED"


def test_empty_refresh_and_missing_revision_fail_closed() -> None:
    client = _client()
    empty = client.post(
        "/internal/v1/indexes/refresh",
        headers={"X-Internal-Token": TOKEN},
        json={"revision": "empty-api", "references": []},
    )
    assert empty.status_code == 200
    assert empty.json()["referenceCount"] == 0

    missing = client.post(
        "/internal/v1/query",
        headers={"X-Internal-Token": TOKEN},
        data={
            "revision": "not-loaded",
            "topK": "5",
            "descriptorVersion": ALGORITHM_VERSION,
            "channel": "UNMASKED",
            "exclusionBoxes": "[]",
        },
        files={"image": ("ignored.png", synthetic_image(801), "image/png")},
    )
    assert missing.status_code == 409
    assert missing.json()["error"]["code"] == "reference_revision_not_loaded"


def test_request_limits_versions_and_top_k_are_strict() -> None:
    client = _client()
    source = synthetic_image(901)
    wrong_version = client.post(
        "/internal/v1/descriptors/compile",
        headers={"X-Internal-Token": TOKEN},
        data={
            "descriptorVersion": "orb-unknown",
            "channel": "UNMASKED",
            "exclusionBoxes": "[]",
        },
        files={"image": ("ignored.png", source, "image/png")},
    )
    assert wrong_version.status_code == 422
    assert wrong_version.json()["error"]["code"] == "descriptor_version_mismatch"

    invalid_top_k = client.post(
        "/internal/v1/query",
        headers={"X-Internal-Token": TOKEN},
        data={
            "revision": "any",
            "topK": "6",
            "descriptorVersion": ALGORITHM_VERSION,
            "channel": "UNMASKED",
            "exclusionBoxes": "[]",
        },
        files={"image": ("ignored.png", source, "image/png")},
    )
    assert invalid_top_k.status_code == 422
    assert invalid_top_k.json()["error"]["code"] == "request_validation_failed"

    oversized = client.post(
        "/internal/v1/descriptors/compile",
        headers={
            "X-Internal-Token": TOKEN,
            "Content-Type": "multipart/form-data; boundary=x",
            "Content-Length": str(10 * 1024 * 1024),
        },
        content=b"",
    )
    assert oversized.status_code == 413
    assert oversized.json()["error"]["code"] == "request_too_large"
