package com.oneenterprise.orderservice.client;

import java.math.BigDecimal;

public record PaymentChargeRequest(Long orderId, BigDecimal amount) {
}
