from __future__ import annotations


class ServiceError(Exception):
    def __init__(self, status_code: int, code: str, message: str) -> None:
        super().__init__(message)
        self.status_code = status_code
        self.code = code
        self.message = message


class InvalidImageError(ServiceError):
    def __init__(self, message: str) -> None:
        super().__init__(422, "invalid_image", message)


class InvalidDescriptorError(ServiceError):
    def __init__(self, message: str) -> None:
        super().__init__(422, "invalid_descriptor_payload", message)


class SnapshotConflictError(ServiceError):
    def __init__(self) -> None:
        super().__init__(
            409,
            "revision_conflict",
            "the revision is already loaded with different immutable content",
        )


class RevisionNotLoadedError(ServiceError):
    def __init__(self) -> None:
        super().__init__(
            409,
            "reference_revision_not_loaded",
            "the exact reference revision is not loaded; refresh it before querying",
        )


class ProcessingTimeoutError(ServiceError):
    def __init__(self) -> None:
        super().__init__(504, "processing_timeout", "bounded processing time expired")


class CapacityError(ServiceError):
    def __init__(self, message: str) -> None:
        super().__init__(413, "capacity_limit_exceeded", message)
