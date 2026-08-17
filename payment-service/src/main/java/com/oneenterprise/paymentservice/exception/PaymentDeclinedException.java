package com.oneenterprise.paymentservice.exception;

public class PaymentDeclinedException extends RuntimeException {

    public PaymentDeclinedException(Long orderId) {
        super("Payment for order " + orderId + " was declined");
    }
}
