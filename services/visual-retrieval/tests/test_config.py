from __future__ import annotations

import pytest

from app.config import Settings


def test_compose_empty_token_is_absent_in_explicit_local_mode(monkeypatch) -> None:
    monkeypatch.setenv("VISUAL_RETRIEVAL_INTERNAL_TOKEN", "")
    monkeypatch.setenv("VISUAL_RETRIEVAL_ALLOW_UNAUTHENTICATED", "true")

    settings = Settings.from_env()

    assert settings.internal_token is None
    assert settings.authentication_ready is True


def test_internal_token_has_bounded_length(monkeypatch) -> None:
    monkeypatch.setenv("VISUAL_RETRIEVAL_INTERNAL_TOKEN", "x" * 513)
    monkeypatch.setenv("VISUAL_RETRIEVAL_ALLOW_UNAUTHENTICATED", "false")

    with pytest.raises(RuntimeError, match="32 to 512 URL-safe"):
        Settings.from_env()


def test_internal_token_rejects_header_control_characters(monkeypatch) -> None:
    monkeypatch.setenv(
        "VISUAL_RETRIEVAL_INTERNAL_TOKEN", "x" * 31 + "\nheader-injection"
    )
    monkeypatch.setenv("VISUAL_RETRIEVAL_ALLOW_UNAUTHENTICATED", "false")

    with pytest.raises(RuntimeError, match="URL-safe"):
        Settings.from_env()
