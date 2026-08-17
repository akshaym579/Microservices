package com.oneenterprise.paymentservice.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(PaymentDeclinedException.class)
    public ResponseEntity<Map<String, Object>> handleDeclined(PaymentDeclinedException ex,
                                                              HttpServletRequest request) {
        return build(HttpStatus.PAYMENT_REQUIRED, "PAYMENT_DECLINED", ex.getMessage(), request);
    }

    @ExceptionHandler(PaymentProcessingException.class)
    public ResponseEntity<Map<String, Object>> handleProcessing(PaymentProcessingException ex,
                                                                HttpServletRequest request) {
        return build(HttpStatus.SERVICE_UNAVAILABLE, "PAYMENT_PROVIDER_UNAVAILABLE", ex.getMessage(), request);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleStatus(ResponseStatusException ex,
                                                            HttpServletRequest request) {
        HttpStatus status = HttpStatus.valueOf(ex.getStatusCode().value());
        return build(status, "PAYMENT_NOT_FOUND", ex.getReason(), request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleBadParameter(MethodArgumentTypeMismatchException ex,
                                                                  HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER",
                "'" + ex.getName() + "' has an unusable value '" + ex.getValue() + "'", request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "Unexpected error in payment-service", request);
    }

    private ResponseEntity<Map<String, Object>> build(HttpStatus status, String code, String message,
                                                      HttpServletRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("code", code);
        body.put("message", message);
        body.put("path", request.getRequestURI());
        return ResponseEntity.status(status).body(body);
    }
}
