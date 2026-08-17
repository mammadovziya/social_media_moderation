#!/usr/bin/env python3
"""Deterministic OpenAI-compatible stub for latency and fault testing."""

from __future__ import annotations

import json
import os
import re
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any, BinaryIO


MODERATION_CATEGORIES = (
    "harassment",
    "harassment/threatening",
    "hate",
    "hate/threatening",
    "illicit",
    "illicit/violent",
    "self-harm",
    "self-harm/intent",
    "self-harm/instructions",
    "sexual",
    "sexual/minors",
    "violence",
    "violence/graphic",
)

SAFE_DEFAULTS: dict[str, Any] = {
    "safetyDisposition": "allow_none",
    "domain": "investment_related",
    "financialClaim": "analysis",
    "financialRisk": "none",
    "financialPrivacy": "none",
    "impersonation": "none",
    "restrictedPoliticalEntity": "none",
    "politicalContext": "none",
    "adjudicationMode": "candidate_recheck",
    "action": "allow",
    "safetyAction": "allow",
    "category": "none",
    "finalReason": "none",
    "candidateDisposition": "rejected",
    "evidenceBasis": "current_text",
    "reasonCode": "current_content_safe",
}

REQUEST_COUNT = 0
REQUEST_LOCK = threading.Lock()


class RequestBodyError(ValueError):
    def __init__(self, status: int, message: str) -> None:
        super().__init__(message)
        self.status = status
        self.message = message


def read_chunked_body(stream: BinaryIO, maximum_bytes: int) -> bytes:
    body = bytearray()
    while True:
        size_line = stream.readline(130)
        if not size_line.endswith(b"\n") or len(size_line) > 129:
            raise RequestBodyError(400, "invalid chunk header")
        try:
            size_token = size_line.strip().split(b";", 1)[0]
            chunk_size = int(size_token, 16)
        except ValueError as exception:
            raise RequestBodyError(400, "invalid chunk size") from exception
        if chunk_size < 0 or len(body) + chunk_size > maximum_bytes:
            raise RequestBodyError(413, "request body exceeds limit")
        if chunk_size == 0:
            trailer_bytes = 0
            while True:
                trailer = stream.readline(8194)
                trailer_bytes += len(trailer)
                if not trailer.endswith(b"\n") or trailer_bytes > 8192:
                    raise RequestBodyError(400, "invalid chunk trailer")
                if trailer in (b"\r\n", b"\n"):
                    return bytes(body)
        chunk = stream.read(chunk_size)
        if len(chunk) != chunk_size or stream.read(2) != b"\r\n":
            raise RequestBodyError(400, "truncated chunked body")
        body.extend(chunk)


def integer_env(name: str, default: int, minimum: int = 0) -> int:
    raw = os.getenv(name, str(default))
    try:
        value = int(raw)
    except ValueError as exception:
        raise SystemExit(f"{name} must be an integer") from exception
    if value < minimum:
        raise SystemExit(f"{name} must be at least {minimum}")
    return value


DELAY_MS = integer_env("FAKE_OPENAI_DELAY_MS", 50)
ERROR_EVERY = integer_env("FAKE_OPENAI_ERROR_EVERY", 0)
ERROR_STATUS = integer_env("FAKE_OPENAI_ERROR_STATUS", 429, 400)
PORT = integer_env("PORT", 8000, 1)
MAX_BODY_BYTES = integer_env("FAKE_OPENAI_MAX_BODY_BYTES", 32 * 1024 * 1024, 1)


def next_request_number() -> int:
    global REQUEST_COUNT
    with REQUEST_LOCK:
        REQUEST_COUNT += 1
        return REQUEST_COUNT


def enum_or_default(definition: dict[str, Any], preferred: Any) -> Any:
    allowed = definition.get("enum")
    if isinstance(allowed, list) and allowed:
        return preferred if preferred in allowed else allowed[0]
    return preferred


def candidate_ids(payload: dict[str, Any]) -> list[str]:
    serialized = json.dumps(payload, ensure_ascii=False)
    matches = re.findall(
        r'\\"(?:candidateId|referenceId)\\"\s*:\s*\\"([^\"]+)\\"',
        serialized,
    )
    return list(dict.fromkeys(matches))[:8]


def structured_output(payload: dict[str, Any]) -> dict[str, Any]:
    text_format = payload.get("text", {}).get("format", {})
    schema = text_format.get("schema", {})
    properties = schema.get("properties", {})
    required = schema.get("required", [])
    candidates = candidate_ids(payload)
    output: dict[str, Any] = {}
    for name in required:
        definition = properties.get(name, {})
        if name == "candidateIds":
            minimum = int(definition.get("minItems", 0))
            values = candidates
            if minimum > 0 and not values:
                values = ["fake-reference"]
            output[name] = values
            continue
        preferred = SAFE_DEFAULTS.get(name)
        field_type = definition.get("type")
        if preferred is None:
            if field_type == "boolean":
                preferred = False
            elif field_type in ("integer", "number"):
                preferred = 0
            elif field_type == "array":
                preferred = []
            elif field_type == "object":
                preferred = {}
            else:
                preferred = "none"
        output[name] = enum_or_default(definition, preferred)
    return output


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt: str, *args: Any) -> None:
        if os.getenv("FAKE_OPENAI_ACCESS_LOG", "false").lower() == "true":
            super().log_message(fmt, *args)

    def send_json(self, status: int, value: dict[str, Any]) -> None:
        encoded = json.dumps(value, separators=(",", ":")).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(encoded)))
        self.send_header("Connection", "close" if self.close_connection else "keep-alive")
        self.end_headers()
        self.wfile.write(encoded)

    def do_GET(self) -> None:  # noqa: N802
        if self.path in ("/health", "/healthz"):
            self.send_json(200, {"status": "ok", "requests": REQUEST_COUNT})
            return
        self.send_json(404, {"error": {"message": "not found"}})

    def do_POST(self) -> None:  # noqa: N802
        try:
            content_length_header = self.headers.get("Content-Length")
            transfer_encoding = self.headers.get("Transfer-Encoding", "").lower()
            if content_length_header is not None:
                content_length = int(content_length_header)
                if content_length <= 0 or content_length > MAX_BODY_BYTES:
                    raise RequestBodyError(413, "invalid request size")
                raw_body = self.rfile.read(content_length)
                if len(raw_body) != content_length:
                    raise RequestBodyError(400, "truncated request body")
            elif "chunked" in transfer_encoding:
                raw_body = read_chunked_body(self.rfile, MAX_BODY_BYTES)
                if not raw_body:
                    raise RequestBodyError(413, "invalid request size")
            else:
                raise RequestBodyError(411, "request length required")
        except (RequestBodyError, ValueError) as exception:
            error = exception if isinstance(exception, RequestBodyError) else RequestBodyError(
                400, "invalid content length"
            )
            self.close_connection = True
            self.send_json(error.status, {"error": {"message": error.message}})
            return
        try:
            payload = json.loads(raw_body)
        except (UnicodeDecodeError, json.JSONDecodeError):
            self.send_json(400, {"error": {"message": "invalid JSON"}})
            return

        request_number = next_request_number()
        if DELAY_MS:
            time.sleep(DELAY_MS / 1000.0)
        if ERROR_EVERY and request_number % ERROR_EVERY == 0:
            self.send_json(
                ERROR_STATUS,
                {"error": {"message": "injected deterministic failure", "type": "load_test"}},
            )
            return

        model = str(payload.get("model", "fake-model"))
        if self.path in ("/moderations", "/v1/moderations"):
            categories = {name: False for name in MODERATION_CATEGORIES}
            scores = {name: 0.0 for name in MODERATION_CATEGORIES}
            self.send_json(
                200,
                {
                    "id": f"modr-fake-{request_number}",
                    "model": model,
                    "results": [
                        {
                            "flagged": False,
                            "categories": categories,
                            "category_scores": scores,
                        }
                    ],
                },
            )
            return

        if self.path in ("/responses", "/v1/responses"):
            output = json.dumps(structured_output(payload), separators=(",", ":"))
            self.send_json(
                200,
                {
                    "id": f"resp-fake-{request_number}",
                    "object": "response",
                    "status": "completed",
                    "error": None,
                    "incomplete_details": None,
                    "model": model,
                    "output": [
                        {
                            "type": "message",
                            "status": "completed",
                            "role": "assistant",
                            "content": [{"type": "output_text", "text": output}],
                        }
                    ],
                    "usage": {
                        "input_tokens": 100,
                        "input_tokens_details": {"cached_tokens": 0},
                        "output_tokens": 20,
                        "total_tokens": 120,
                    },
                },
            )
            return

        self.send_json(404, {"error": {"message": "not found"}})


if __name__ == "__main__":
    server = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    server.daemon_threads = True
    server.serve_forever()
