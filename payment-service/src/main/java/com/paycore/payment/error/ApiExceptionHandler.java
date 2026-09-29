package com.paycore.payment.error;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;

import java.util.Comparator;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Renders MVC-level failures — bean validation, a malformed or unreadable body, a missing header,
 * a mistyped path variable, a lost optimistic-lock race, and our own {@link ApiException} refusals
 * — in the platform's JSON error shape. It does not see everything: {@code JsonAuthenticationEntryPoint}
 * and {@code JsonAccessDeniedHandler} answer authentication and authorization failures before a
 * request ever reaches MVC. {@code detail}, when this class writes one, is always text authored
 * here, never an exception's message.
 *
 * <p>Every response here presets its content type to JSON. Without that, a client whose
 * {@code Accept} header cannot be satisfied by {@code application/json} would send the error
 * response back through content negotiation, which fails it a second time and — depending on the
 * container — can render as an empty body instead of this shape.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private static final int MAX_ECHOED_FIELD_NAME = 64;

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Map<String, Object>> refused(ApiException e, HttpServletRequest request) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(e.status()).contentType(MediaType.APPLICATION_JSON);
        if (e.retryLater()) {
            response.header(HttpHeaders.RETRY_AFTER, "1");
        }
        return response.body(ErrorBody.of(e.status(), request.getRequestURI(), e.detail(), e.extras()));
    }

    /** Field names and our own messages, sorted so the detail is stable even when two fields tie. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, Object>> invalidBody(MethodArgumentNotValidException e, HttpServletRequest request) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .sorted(Comparator.comparing(FieldError::getField)
                        .thenComparing(FieldError::getDefaultMessage, Comparator.nullsFirst(Comparator.naturalOrder())))
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return badRequest(request, detail);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, Object>> unreadableBody(HttpMessageNotReadableException e, HttpServletRequest request) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof UnrecognizedPropertyException unknown) {
                return badRequest(request, "unknown field: " + truncate(unknown.getPropertyName()));
            }
        }
        return badRequest(request, "malformed request body");
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<Map<String, Object>> missingHeader(MissingRequestHeaderException e, HttpServletRequest request) {
        return badRequest(request, e.getHeaderName() + " header is required");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<Map<String, Object>> malformedParameter(MethodArgumentTypeMismatchException e, HttpServletRequest request) {
        return badRequest(request, "malformed " + e.getName());
    }

    /** A lost race that escaped the code expecting it: still a conflict, never a 500. */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    ResponseEntity<Map<String, Object>> concurrentModification(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON)
                .body(ErrorBody.of(HttpStatus.CONFLICT, request.getRequestURI(), "payment was modified concurrently; retry"));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, Object>> unexpected(Exception e, HttpServletRequest request) {
        if (e instanceof ErrorResponse framework) {
            // Spring MVC's own refusals: an unreadable Accept or Content-Type, a method the route
            // doesn't support, and the like — not only 4xx, though a 5xx here is rare. A request
            // with genuinely no mapping never reaches this class: the security filter chain
            // refuses it first, as DetailedDenial("no such operation"). Headers such as Allow or
            // Accept carry information the client needs, so they are kept.
            HttpStatus status = HttpStatus.valueOf(framework.getStatusCode().value());
            if (status.is5xxServerError()) {
                log.warn("framework failure on {} {}: {}", request.getMethod(), request.getRequestURI(), e.toString());
            }
            return ResponseEntity.status(status)
                    .headers(framework.getHeaders())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(ErrorBody.of(status, request.getRequestURI()));
        }
        log.error("unhandled failure on {} {}", request.getMethod(), request.getRequestURI(), e);
        return ResponseEntity.internalServerError().contentType(MediaType.APPLICATION_JSON)
                .body(ErrorBody.of(HttpStatus.INTERNAL_SERVER_ERROR, request.getRequestURI()));
    }

    private static ResponseEntity<Map<String, Object>> badRequest(HttpServletRequest request, String detail) {
        return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON)
                .body(ErrorBody.of(HttpStatus.BAD_REQUEST, request.getRequestURI(), detail));
    }

    private static String truncate(String value) {
        return value.length() <= MAX_ECHOED_FIELD_NAME ? value : value.substring(0, MAX_ECHOED_FIELD_NAME) + "…";
    }
}
