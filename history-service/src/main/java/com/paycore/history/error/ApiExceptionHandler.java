package com.paycore.history.error;

import com.paycore.history.history.NoHistoryException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Map;

/** The platform error shape for failures that reach MVC; detail is always our own text. */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(NoHistoryException.class)
    ResponseEntity<Map<String, Object>> noHistory(HttpServletRequest request) {
        return json(HttpStatus.NOT_FOUND, request, "no history for this payment");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<Map<String, Object>> malformed(MethodArgumentTypeMismatchException e, HttpServletRequest request) {
        return json(HttpStatus.BAD_REQUEST, request, "malformed " + e.getName());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, Object>> unexpected(Exception e, HttpServletRequest request) {
        if (e instanceof ErrorResponse framework) {
            HttpStatus status = HttpStatus.valueOf(framework.getStatusCode().value());
            return ResponseEntity.status(status).headers(framework.getHeaders())
                    .contentType(MediaType.APPLICATION_JSON).body(ErrorBody.of(status, request.getRequestURI()));
        }
        log.error("unhandled failure on {} {}", request.getMethod(), request.getRequestURI(), e);
        return ResponseEntity.internalServerError().contentType(MediaType.APPLICATION_JSON)
                .body(ErrorBody.of(HttpStatus.INTERNAL_SERVER_ERROR, request.getRequestURI()));
    }

    private static ResponseEntity<Map<String, Object>> json(HttpStatus status, HttpServletRequest request, String detail) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                .body(ErrorBody.of(status, request.getRequestURI(), detail));
    }
}
