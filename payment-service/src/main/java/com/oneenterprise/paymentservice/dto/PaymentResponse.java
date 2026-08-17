package com.oneenterprise.paymentservice.dto;

import com.oneenterprise.paymentservice.model.Payment;

import java.math.BigDecimal;

public record PaymentResponse(String paymentId, Long orderId, BigDecimal amount, String status) {

    public static PaymentResponse from(Payment payment) {
        return new PaymentResponse(payment.getPaymentId(), payment.getOrderId(),
                payment.getAmount(), payment.getStatus());
    }
}
