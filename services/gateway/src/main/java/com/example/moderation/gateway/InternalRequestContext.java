package com.example.moderation.gateway;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** Request correlation values forwarded for diagnostics but excluded from work identities. */
final class InternalRequestContext {
    static final String REQUEST_ID_HEADER = "X-Request-ID";
    static final String TRACEPARENT_HEADER = "traceparent";
    private static final Pattern TRACEPARENT = Pattern.compile(
            "[0-9a-f]{2}-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}");
    private static final InheritableThreadLocal<Values> CURRENT =
            new InheritableThreadLocal<>();

    private InternalRequestContext() {}

    static Scope open(String suppliedRequestId, String suppliedTraceparent) {
        Values previous = CURRENT.get();
        String requestId = RequestIdentifiers.resolve(suppliedRequestId);
        String traceparent = validTraceparent(suppliedTraceparent)
                ? suppliedTraceparent
                : null;
        CURRENT.set(new Values(requestId, traceparent));
        return () -> {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        };
    }

    static String requestId(String supplied) {
        Values values = CURRENT.get();
        return values == null ? RequestIdentifiers.resolve(supplied) : values.requestId();
    }

    static Map<String, String> forwardingHeaders() {
        Values values = CURRENT.get();
        if (values == null) {
            return Map.of();
        }
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(REQUEST_ID_HEADER, values.requestId());
        if (values.traceparent() != null) {
            headers.put(TRACEPARENT_HEADER, values.traceparent());
        }
        return Map.copyOf(headers);
    }

    private static boolean validTraceparent(String value) {
        if (value == null || !TRACEPARENT.matcher(value).matches()) {
            return false;
        }
        String traceId = value.substring(3, 35);
        String parentId = value.substring(36, 52);
        return !traceId.chars().allMatch(character -> character == '0')
                && !parentId.chars().allMatch(character -> character == '0');
    }

    private record Values(String requestId, String traceparent) {}

    @FunctionalInterface
    interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
