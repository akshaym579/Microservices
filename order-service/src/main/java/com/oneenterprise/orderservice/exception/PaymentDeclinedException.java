package com.oneenterprise.orderservice.exception;

public class PaymentDeclinedException extends PaymentException {

    public PaymentDeclinedException(String message) {
        super(Reason.PAYMENT_DECLINED, message);
    }
}
