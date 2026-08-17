package com.oneenterprise.orderservice.dto;

import java.math.BigDecimal;

public record CreateOrderRequest(Long userId, String product, BigDecimal amount) {
}
