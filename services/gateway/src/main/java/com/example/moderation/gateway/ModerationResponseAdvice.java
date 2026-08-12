package com.example.moderation.gateway;

import com.example.moderation.gateway.api.ModerationResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.CacheControl;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.AbstractJackson2HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.converter.json.MappingJacksonValue;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/** Keeps the public response minimal while allowing authenticated local evaluation tooling. */
@RestControllerAdvice
final class ModerationResponseAdvice implements ResponseBodyAdvice<Object> {
    static final String INTERNAL_TOKEN_HEADER = "X-Moderation-Internal-Token";

    private final byte[] internalToken;

    ModerationResponseAdvice(ModerationProperties properties) {
        this.internalToken = properties.internalResponseToken()
                .getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public boolean supports(
            MethodParameter returnType,
            Class<? extends HttpMessageConverter<?>> converterType) {
        return AbstractJackson2HttpMessageConverter.class.isAssignableFrom(converterType);
    }

    @Override
    public Object beforeBodyWrite(
            Object body,
            MethodParameter returnType,
            MediaType selectedContentType,
            Class<? extends HttpMessageConverter<?>> selectedConverterType,
            ServerHttpRequest request,
            ServerHttpResponse response) {
        if (!(body instanceof ModerationResponse)) {
            return body;
        }
        response.getHeaders().setCacheControl(CacheControl.noStore().cachePrivate());
        response.getHeaders().add("Vary", INTERNAL_TOKEN_HEADER);
        if (authenticated(request)) {
            return body;
        }
        MappingJacksonValue result = new MappingJacksonValue(body);
        result.setSerializationView(ModerationResponse.Public.class);
        return result;
    }

    private boolean authenticated(ServerHttpRequest request) {
        String supplied = request.getHeaders().getFirst(INTERNAL_TOKEN_HEADER);
        return internalToken.length > 0
                && supplied != null
                && MessageDigest.isEqual(
                        internalToken,
                        supplied.getBytes(StandardCharsets.UTF_8));
    }
}
