package com.paycore.payment.error;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Replaces Boot's {@code BasicErrorController}, so a failure that never reaches MVC still answers
 * in the platform shape. Those are the ones {@link ApiExceptionHandler} cannot see: an exception
 * thrown inside the security filters (AuthCore's JWKS unreachable while a token is verified) or a
 * request the firewall rejects (a {@code ;} in the path). Both end in an ERROR dispatch to
 * {@code /error}, which the filter chain permits.
 *
 * <p>No {@code detail}: all this class knows is a status code, and whatever caused it is not
 * text we wrote. The content type is preset, as in {@link ApiExceptionHandler}, so the body is
 * JSON whatever the client's {@code Accept} says.
 */
@RestController
public class JsonErrorController implements ErrorController {

    @RequestMapping("/error")
    ResponseEntity<Map<String, Object>> error(HttpServletRequest request) {
        HttpStatus status = statusOf(request);
        Object uri = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        String path = uri instanceof String original ? original : request.getRequestURI();
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                .body(ErrorBody.of(status, path));
    }

    private static HttpStatus statusOf(HttpServletRequest request) {
        Object code = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        HttpStatus status = code instanceof Integer value ? HttpStatus.resolve(value) : null;
        return status != null ? status : HttpStatus.INTERNAL_SERVER_ERROR;
    }
}
