package com.paycore.payment.error;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
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
 * Renders every failure that reaches MVC in the platform shape. {@code detail} is always text
 * written here — never an exception's message.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Map<String, Object>> refused(ApiException e, HttpServletRequest request) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(e.status());
        if (e.retryLater()) {
            response.header(HttpHeaders.RETRY_AFTER, "1");
        }
        return response.body(ErrorBody.of(e.status(), request.getRequestURI(), e.detail(), e.extras()));
    }

    /** Field names and our own messages, sorted so the detail is stable. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, Object>> invalidBody(MethodArgumentNotValidException e, HttpServletRequest request) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .sorted(Comparator.comparing(FieldError::getField))
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return badRequest(request, detail);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, Object>> unreadableBody(HttpMessageNotReadableException e, HttpServletRequest request) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof UnrecognizedPropertyException unknown) {
                return badRequest(request, "unknown field: " + unknown.getPropertyName());
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
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorBody.of(HttpStatus.CONFLICT,
                request.getRequestURI(), "payment was modified concurrently; retry"));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, Object>> unexpected(Exception e, HttpServletRequest request) {
        if (e instanceof ErrorResponse framework) {
            // Spring MVC's own 4xx (unsupported media type, no such route, ...): keep its status.
            HttpStatus status = HttpStatus.valueOf(framework.getStatusCode().value());
            return ResponseEntity.status(status).body(ErrorBody.of(status, request.getRequestURI()));
        }
        log.error("unhandled failure on {} {}", request.getMethod(), request.getRequestURI(), e);
        return ResponseEntity.internalServerError()
                .body(ErrorBody.of(HttpStatus.INTERNAL_SERVER_ERROR, request.getRequestURI()));
    }

    private static ResponseEntity<Map<String, Object>> badRequest(HttpServletRequest request, String detail) {
        return ResponseEntity.badRequest().body(ErrorBody.of(HttpStatus.BAD_REQUEST, request.getRequestURI(), detail));
    }
}
