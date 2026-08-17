package com.oneenterprise.orderservice.exception;

import org.springframework.http.HttpStatus;

public class UserServiceException extends RuntimeException {

    public enum Reason {


        USER_NOT_FOUND(HttpStatus.NOT_FOUND),


        USER_SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE),


        USER_SERVICE_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT),

        USER_SERVICE_ERROR(HttpStatus.BAD_GATEWAY);

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

    public UserServiceException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
