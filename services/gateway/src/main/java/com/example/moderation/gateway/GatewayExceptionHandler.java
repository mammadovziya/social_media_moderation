package com.example.moderation.gateway;

import com.example.moderation.gateway.api.ApiError;
import com.example.moderation.gateway.api.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolationException;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class GatewayExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GatewayExceptionHandler.class);
    private static final String REQUEST_ID_HEADER = "X-Request-ID";

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handle(
            Exception exception,
            HttpServletRequest request,
            HttpServletResponse response) {
        HttpStatusCode status = status(exception);
        ErrorCode code = errorCode(status);
        String requestId = requestId(request, response);
        String message = message(exception, status);

        if (status.is5xxServerError()) {
            log.error(
                    "gateway request failed requestId={} status={} error={}",
                    requestId,
                    status.value(),
                    code,
                    exception);
        } else {
            log.warn(
                    "gateway request rejected requestId={} status={} error={}",
                    requestId,
                    status.value(),
                    code);
        }
        return ResponseEntity.status(status).body(new ApiError(code, message, requestId));
    }

    private static HttpStatusCode status(Exception exception) {
        if (exception instanceof ResponseStatusException responseStatus) {
            return responseStatus.getStatusCode();
        }
        if (exception instanceof MaxUploadSizeExceededException) {
            return HttpStatus.PAYLOAD_TOO_LARGE;
        }
        if (exception instanceof HttpMediaTypeNotSupportedException) {
            return HttpStatus.UNSUPPORTED_MEDIA_TYPE;
        }
        if (exception instanceof MissingServletRequestParameterException
                || exception instanceof HandlerMethodValidationException
                || exception instanceof ConstraintViolationException
                || exception instanceof MultipartException) {
            return HttpStatus.BAD_REQUEST;
        }
        if (exception instanceof ErrorResponse errorResponse) {
            return errorResponse.getStatusCode();
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }

    private static ErrorCode errorCode(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> ErrorCode.INVALID_INPUT;
            case 404 -> ErrorCode.NOT_FOUND;
            case 405 -> ErrorCode.METHOD_NOT_ALLOWED;
            case 406 -> ErrorCode.NOT_ACCEPTABLE;
            case 413 -> ErrorCode.PAYLOAD_TOO_LARGE;
            case 415 -> ErrorCode.UNSUPPORTED_MEDIA_TYPE;
            case 422 -> ErrorCode.UNPROCESSABLE_IMAGE;
            case 503 -> ErrorCode.SERVICE_UNAVAILABLE;
            default -> status.is5xxServerError()
                    ? ErrorCode.INTERNAL_ERROR
                    : ErrorCode.INVALID_INPUT;
        };
    }

    private static String message(Exception exception, HttpStatusCode status) {
        if (status.is5xxServerError()) {
            return status.value() == 503
                    ? "Service is not available."
                    : "Internal server error.";
        }
        if (exception instanceof ResponseStatusException responseStatus
                && responseStatus.getReason() != null
                && !responseStatus.getReason().isBlank()) {
            return sentence(responseStatus.getReason());
        }
        if (exception instanceof MissingServletRequestParameterException missing) {
            return missing.getParameterName() + " is required.";
        }
        if (exception instanceof HandlerMethodValidationException validation) {
            return validation.getParameterValidationResults().stream()
                    .findFirst()
                    .map(result -> {
                        String name = result.getMethodParameter().getParameterName();
                        String detail = result.getResolvableErrors().stream()
                                .map(MessageSourceResolvable::getDefaultMessage)
                                .filter(Objects::nonNull)
                                .findFirst()
                                .orElse("is invalid");
                        return sentence(name == null ? detail : name + " " + detail);
                    })
                    .orElse("Request validation failed.");
        }
        if (exception instanceof ConstraintViolationException validation) {
            return validation.getConstraintViolations().stream()
                    .findFirst()
                    .map(violation -> {
                        String path = violation.getPropertyPath().toString();
                        int separator = path.lastIndexOf('.');
                        String name = separator < 0 ? path : path.substring(separator + 1);
                        return sentence(name + " " + violation.getMessage());
                    })
                    .orElse("Request validation failed.");
        }
        if (exception instanceof MaxUploadSizeExceededException) {
            return "Image exceeds the size limit.";
        }
        if (exception instanceof HttpMediaTypeNotSupportedException) {
            return "Content-Type must be multipart/form-data.";
        }
        if (exception instanceof MultipartException) {
            return "Invalid multipart request.";
        }
        return switch (status.value()) {
            case 404 -> "Endpoint not found.";
            case 405 -> "HTTP method is not supported.";
            case 406 -> "Requested response type is not supported.";
            case 413 -> "Image exceeds the size limit.";
            case 415 -> "Media type is not supported.";
            case 422 -> "Image could not be processed.";
            default -> "Request is invalid.";
        };
    }

    private static String sentence(String value) {
        String message = value.trim();
        if (message.endsWith(".") || message.endsWith("!") || message.endsWith("?")) {
            return message;
        }
        return message + ".";
    }

    private static String requestId(
            HttpServletRequest request, HttpServletResponse response) {
        String requestId = response.getHeader(REQUEST_ID_HEADER);
        if (requestId == null || requestId.isBlank()) {
            requestId = request.getHeader(REQUEST_ID_HEADER);
        }
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }
        response.setHeader(REQUEST_ID_HEADER, requestId);
        return requestId;
    }
}
