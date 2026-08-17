package com.oneenterprise.orderservice.dto;

import java.math.BigDecimal;

public record OrderResponse(
        Long orderId,
        String product,
        BigDecimal amount,
        String status,
        CustomerSummary customer,
        PaymentSummary payment) {
}
