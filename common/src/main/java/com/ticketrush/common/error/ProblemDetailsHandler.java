package com.ticketrush.common.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps exceptions to RFC 9457 ProblemDetails with a stable {@code code}. Registered only through
 * {@link CommonErrorAutoConfiguration} (servlet apps), never by component scanning.
 */
@RestControllerAdvice
public class ProblemDetailsHandler {

    private static final Logger log = LoggerFactory.getLogger(ProblemDetailsHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ProblemDetail> handleApi(ApiException ex) {
        ResponseEntity<ProblemDetail> response = problem(ex.code(), ex.getMessage(), null);
        ex.properties().forEach(response.getBody()::setProperty);
        return response;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(fe -> errors.putIfAbsent(fe.getField(), fe.getDefaultMessage()));
        return problem(ErrorCode.VALIDATION_FAILED, "Request validation failed", errors);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handleUnreadable(HttpMessageNotReadableException ex) {
        return problem(ErrorCode.VALIDATION_FAILED, "Malformed request body", null);
    }

    /** e.g. /api/events/abc or ?page=x: the client sent a value of the wrong type (400, not 500). */
    @ExceptionHandler(TypeMismatchException.class)
    public ResponseEntity<ProblemDetail> handleTypeMismatch(TypeMismatchException ex) {
        String field = ex.getPropertyName() != null ? ex.getPropertyName() : "value";
        String expected = ex.getRequiredType() != null ? ex.getRequiredType().getSimpleName() : "a different type";
        return problem(ErrorCode.VALIDATION_FAILED, "Invalid value for '" + field + "'", Map.of(field, "must be " + expected));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleOther(Exception ex) throws Exception {
        // Let Spring Security's entry point / access-denied handler produce 401/403.
        if (ex instanceof AccessDeniedException || ex instanceof AuthenticationException) {
            throw ex;
        }
        // Spring MVC's own errors (404, 405, 415, ...) keep their status.
        if (ex instanceof ErrorResponse er) {
            return ResponseEntity.status(er.getStatusCode()).body(er.getBody());
        }
        log.error("Unhandled exception", ex);
        return problem(ErrorCode.INTERNAL_ERROR, "Unexpected error", null);
    }

    private static ResponseEntity<ProblemDetail> problem(ErrorCode code, String detail, Map<String, String> errors) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.valueOf(code.status()), detail);
        pd.setProperty("code", code.name());
        if (errors != null) {
            pd.setProperty("errors", errors);
        }
        return ResponseEntity.status(code.status()).body(pd);
    }
}
