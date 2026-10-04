package com.paycore.history.error;

import com.paycore.history.config.DetailedDenial;
import com.paycore.history.config.PaymentsAuthorization;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 403 in the platform shape. Replaces Spring's {@code BearerTokenAccessDeniedHandler}, which
 * leaves the body empty and labels every denial {@code insufficient_scope} — wrong for a
 * missing tenant, which no scope can fix. No {@code WWW-Authenticate} header: the caller was
 * identified and refused, and offering them a way to authenticate would be nonsense.
 */
@Component
public class JsonAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    public JsonAccessDeniedHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        String detail = accessDeniedException instanceof AuthorizationDeniedException denied
                && denied.getAuthorizationResult() instanceof DetailedDenial reason
                ? reason.detail()
                : PaymentsAuthorization.INSUFFICIENT_PERMISSION;

        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(), ErrorBody.of(HttpStatus.FORBIDDEN, request.getRequestURI(), detail));
    }
}
