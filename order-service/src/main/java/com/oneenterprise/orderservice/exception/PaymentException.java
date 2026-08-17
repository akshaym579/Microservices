package com.oneenterprise.orderservice.exception;

import org.springframework.http.HttpStatus;

public class PaymentException extends RuntimeException {

    public enum Reason {

        PAYMENT_DECLINED(HttpStatus.PAYMENT_REQUIRED),

        PAYMENT_SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE),

        PAYMENT_SERVICE_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT),

        PAYMENT_SERVICE_ERROR(HttpStatus.BAD_GATEWAY),

        PAYMENT_CIRCUIT_OPEN(HttpStatus.SERVICE_UNAVAILABLE);

        private final HttpStatus status;

        Reason(HttpStatus status) {
            this.status = status;
        }

        public HttpStatus getStatus() {
            return status;
        }

        public String getCode() {
            return name();
        }
    }

    private final Reason reason;

    public PaymentException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
