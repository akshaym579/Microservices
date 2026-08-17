package com.oneenterprise.orderservice.exception;

public class PaymentCircuitOpenException extends PaymentException {

    public PaymentCircuitOpenException(String message) {
        super(Reason.PAYMENT_CIRCUIT_OPEN, message);
    }
}
