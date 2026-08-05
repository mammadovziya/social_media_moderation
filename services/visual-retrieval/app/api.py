from __future__ import annotations

import asyncio
import hmac
import json
from collections.abc import Callable
from typing import Annotated, Any, TypeVar

import cv2
from fastapi import Depends, FastAPI, File, Form, Header, Request, UploadFile
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from .config import (
    ALGORITHM_VERSION,
    CANDIDATE_SELECTION_VERSION,
    COMPILE_TIMEOUT_SECONDS,
    MAX_CONCURRENT_CPU_JOBS,
    MAX_IMAGE_BYTES,
    MAX_IMAGE_REQUEST_BYTES,
    MAX_REFRESH_REQUEST_BYTES,
    MAX_REVISION_LENGTH,
    MAX_TOP_K,
    QUERY_TIMEOUT_SECONDS,
    REFRESH_TIMEOUT_SECONDS,
    SERVICE_VERSION,
    Settings,
)
from .engine import VisualRetrievalEngine
from .errors import (
    CapacityError,
    InvalidImageError,
    ProcessingTimeoutError,
    ServiceError,
)
from .image_features import Deadline
from .models import (
    DescriptorChannel,
    DescriptorPayload,
    QueryResponse,
    RefreshRequest,
    RefreshResponse,
)


IMAGE_CONTENT_TYPES = frozenset(
    {"image/jpeg", "image/png", "image/gif", "image/webp"}
)
MAX_EXCLUSION_BOX_JSON_BYTES = 32 * 1024
REVISION_PATTERN = r"^[A-Za-z0-9][A-Za-z0-9._:-]*$"
T = TypeVar("T")


class CpuJobRunner:
    def __init__(self) -> None:
        self._semaphore = asyncio.Semaphore(MAX_CONCURRENT_CPU_JOBS)

    async def run(self, work: Callable[[Deadline], T], timeout: float) -> T:
        deadline = Deadline.after(timeout)
        try:
            await asyncio.wait_for(
                self._semaphore.acquire(), timeout=max(0.001, deadline.remaining)
            )
        except TimeoutError as exc:
            raise ProcessingTimeoutError() from exc

        task = asyncio.create_task(asyncio.to_thread(work, deadline))

        def release_slot(completed: asyncio.Task[T]) -> None:
            self._semaphore.release()
            if not completed.cancelled():
                completed.exception()

        task.add_done_callback(release_slot)
        try:
            return await asyncio.wait_for(
                asyncio.shield(task), timeout=max(0.001, deadline.remaining)
            )
        except TimeoutError as exc:
            raise ProcessingTimeoutError() from exc


def _json_error(status_code: int, code: str, message: str) -> JSONResponse:
    return JSONResponse(
        status_code=status_code,
        content={"error": {"code": code, "message": message}},
        headers={"Cache-Control": "no-store", "X-Content-Type-Options": "nosniff"},
    )


def _parse_exclusion_boxes(raw: str) -> list[list[float]]:
    if len(raw.encode("utf-8")) > MAX_EXCLUSION_BOX_JSON_BYTES:
        raise CapacityError("exclusionBoxes exceeds its bounded JSON size")
    try:
        value = json.loads(raw)
    except json.JSONDecodeError as exc:
        raise InvalidImageError("exclusionBoxes must be valid JSON") from exc
    if not isinstance(value, list):
        raise InvalidImageError("exclusionBoxes must be a JSON array")
    if any(not isinstance(box, list) for box in value):
        raise InvalidImageError("each exclusion box must be a JSON array")
    return value


async def _read_image(upload: UploadFile) -> bytearray:
    if upload.content_type not in IMAGE_CONTENT_TYPES:
        raise ServiceError(
            415,
            "unsupported_image_content_type",
            "image part Content-Type must be image/jpeg, image/png, image/gif, or image/webp",
        )
    content = bytearray()
    try:
        while chunk := await upload.read(64 * 1024):
            if len(content) + len(chunk) > MAX_IMAGE_BYTES:
                raise CapacityError(f"image may contain at most {MAX_IMAGE_BYTES} bytes")
            content.extend(chunk)
    finally:
        await upload.close()
    if not content:
        raise InvalidImageError("image part is empty")
    return content


def create_app(settings: Settings | None = None) -> FastAPI:
    active_settings = settings or Settings.from_env()
    engine = VisualRetrievalEngine()
    cpu_jobs = CpuJobRunner()
    algorithm_ready = cv2.__version__ == "4.12.0"

    application = FastAPI(
        title="Moderation Visual Retrieval",
        version=SERVICE_VERSION,
        docs_url=None,
        redoc_url=None,
        openapi_url=None,
    )
    application.state.engine = engine
    application.state.settings = active_settings

    @application.middleware("http")
    async def enforce_request_envelope(
        request: Request, call_next: Callable[[Request], Any]
    ) -> Any:
        limits = {
            "/internal/v1/descriptors/compile": MAX_IMAGE_REQUEST_BYTES,
            "/internal/v1/query": MAX_IMAGE_REQUEST_BYTES,
            "/internal/v1/indexes/refresh": MAX_REFRESH_REQUEST_BYTES,
        }
        limit = limits.get(request.url.path)
        if request.method == "POST" and limit is not None:
            content_length = request.headers.get("content-length")
            if content_length is None:
                return _json_error(411, "content_length_required", "Content-Length is required")
            try:
                parsed_length = int(content_length)
            except ValueError:
                return _json_error(400, "invalid_content_length", "Content-Length is invalid")
            if parsed_length < 0:
                return _json_error(400, "invalid_content_length", "Content-Length is invalid")
            if parsed_length > limit:
                return _json_error(
                    413,
                    "request_too_large",
                    f"request body may contain at most {limit} bytes",
                )
            content_type = request.headers.get("content-type", "")
            if request.url.path == "/internal/v1/indexes/refresh":
                if not content_type.lower().startswith("application/json"):
                    return _json_error(
                        415, "unsupported_content_type", "refresh requires application/json"
                    )
            elif not content_type.lower().startswith("multipart/form-data"):
                return _json_error(
                    415, "unsupported_content_type", "image endpoints require multipart/form-data"
                )
        response = await call_next(request)
        response.headers["Cache-Control"] = "no-store"
        response.headers["X-Content-Type-Options"] = "nosniff"
        return response

    @application.exception_handler(ServiceError)
    async def service_error_handler(_: Request, error: ServiceError) -> JSONResponse:
        return _json_error(error.status_code, error.code, error.message)

    @application.exception_handler(RequestValidationError)
    async def validation_error_handler(
        _: Request, error: RequestValidationError
    ) -> JSONResponse:
        details = [
            {
                "location": [str(value) for value in issue.get("loc", ())],
                "type": issue.get("type", "validation_error"),
                "message": issue.get("msg", "request validation failed"),
            }
            for issue in error.errors()
        ]
        response = _json_error(422, "request_validation_failed", "request validation failed")
        response.body = json.dumps(
            {"error": {"code": "request_validation_failed", "details": details}},
            separators=(",", ":"),
        ).encode("utf-8")
        response.headers["content-length"] = str(len(response.body))
        return response

    async def require_internal_auth(
        x_internal_token: Annotated[
            str | None, Header(alias="X-Internal-Token")
        ] = None,
    ) -> None:
        if active_settings.internal_token is not None:
            if x_internal_token is None or not hmac.compare_digest(
                x_internal_token, active_settings.internal_token
            ):
                raise ServiceError(
                    401, "unauthorized", "valid internal authentication is required"
                )
            return
        if active_settings.allow_unauthenticated:
            return
        raise ServiceError(
            503,
            "authentication_not_configured",
            "internal authentication is not configured",
        )

    async def require_operational(
        _auth: None = Depends(require_internal_auth),
    ) -> None:
        if not algorithm_ready:
            raise ServiceError(
                503,
                "algorithm_version_mismatch",
                "the pinned OpenCV algorithm version is unavailable",
            )

    @application.get("/health")
    async def health() -> dict[str, object]:
        return {
            "status": "alive",
            "service": "visual-retrieval",
            "version": SERVICE_VERSION,
        }

    @application.get("/ready")
    async def ready() -> JSONResponse:
        is_ready = algorithm_ready and active_settings.authentication_ready
        return JSONResponse(
            status_code=200 if is_ready else 503,
            content={
                "status": "ready" if is_ready else "not_ready",
                "algorithmVersion": ALGORITHM_VERSION,
                "candidateSelectionVersion": CANDIDATE_SELECTION_VERSION,
                "loadedRevisions": engine.cache.size,
            },
            headers={"Cache-Control": "no-store"},
        )

    @application.post(
        "/internal/v1/descriptors/compile",
        response_model=DescriptorPayload,
        dependencies=[Depends(require_operational)],
    )
    async def compile_descriptor(
        image: Annotated[UploadFile, File()],
        descriptor_version: Annotated[str, Form(alias="descriptorVersion")],
        channel: Annotated[DescriptorChannel, Form()],
        exclusion_boxes_json: Annotated[
            str, Form(alias="exclusionBoxes")
        ] = "[]",
    ) -> DescriptorPayload:
        if descriptor_version != ALGORITHM_VERSION:
            raise ServiceError(
                422,
                "descriptor_version_mismatch",
                "descriptorVersion does not match the active algorithm profile",
            )
        boxes = _parse_exclusion_boxes(exclusion_boxes_json)
        raw = await _read_image(image)
        try:
            return await cpu_jobs.run(
                lambda deadline: engine.compile(bytes(raw), channel, boxes, deadline),
                COMPILE_TIMEOUT_SECONDS,
            )
        finally:
            raw.clear()

    @application.post(
        "/internal/v1/indexes/refresh",
        response_model=RefreshResponse,
        dependencies=[Depends(require_operational)],
    )
    async def refresh_index(request: RefreshRequest) -> RefreshResponse:
        return await cpu_jobs.run(
            lambda deadline: engine.refresh(request, deadline),
            REFRESH_TIMEOUT_SECONDS,
        )

    @application.post(
        "/internal/v1/query",
        response_model=QueryResponse,
        dependencies=[Depends(require_operational)],
    )
    async def query(
        image: Annotated[UploadFile, File()],
        revision: Annotated[
            str,
            Form(
                min_length=1,
                max_length=MAX_REVISION_LENGTH,
                pattern=REVISION_PATTERN,
            ),
        ],
        top_k: Annotated[int, Form(alias="topK", ge=1, le=MAX_TOP_K)],
        descriptor_version: Annotated[str, Form(alias="descriptorVersion")],
        channel: Annotated[DescriptorChannel, Form()],
        exclusion_boxes_json: Annotated[
            str, Form(alias="exclusionBoxes")
        ] = "[]",
    ) -> QueryResponse:
        if descriptor_version != ALGORITHM_VERSION:
            raise ServiceError(
                422,
                "descriptor_version_mismatch",
                "descriptorVersion does not match the active algorithm profile",
            )
        boxes = _parse_exclusion_boxes(exclusion_boxes_json)
        raw = await _read_image(image)
        try:
            return await cpu_jobs.run(
                lambda deadline: engine.query(
                    bytes(raw), revision, top_k, channel, boxes, deadline
                ),
                QUERY_TIMEOUT_SECONDS,
            )
        finally:
            raw.clear()

    return application


app = create_app()
