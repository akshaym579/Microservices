package com.oneenterprise.orderservice.client;

import java.math.BigDecimal;

public record PaymentServicePayment(String paymentId, Long orderId, BigDecimal amount, String status) {
}
