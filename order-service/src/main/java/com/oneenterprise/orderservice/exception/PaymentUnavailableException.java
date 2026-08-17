package com.oneenterprise.orderservice.exception;

public class PaymentUnavailableException extends PaymentException {

    public PaymentUnavailableException(Reason reason, String message) {
        super(reason, message);
    }
}
