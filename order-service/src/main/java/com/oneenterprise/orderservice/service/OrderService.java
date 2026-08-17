package com.oneenterprise.orderservice.service;

import com.oneenterprise.orderservice.client.PaymentClient;
import com.oneenterprise.orderservice.client.PaymentServicePayment;
import com.oneenterprise.orderservice.client.UserClient;
import com.oneenterprise.orderservice.client.UserServiceUser;
import com.oneenterprise.orderservice.dto.CreateOrderRequest;
import com.oneenterprise.orderservice.dto.CustomerSummary;
import com.oneenterprise.orderservice.dto.OrderResponse;
import com.oneenterprise.orderservice.dto.PaymentSummary;
import com.oneenterprise.orderservice.exception.OrderNotFoundException;
import com.oneenterprise.orderservice.exception.PaymentDeclinedException;
import com.oneenterprise.orderservice.exception.PaymentException;
import com.oneenterprise.orderservice.model.Order;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    public static final String STATUS_PAID = "PAID";
    public static final String STATUS_PENDING_PAYMENT = "PENDING_PAYMENT";
    public static final String STATUS_PAYMENT_DECLINED = "PAYMENT_DECLINED";

    private final Map<Long, Order> orders = new LinkedHashMap<>();
    private final AtomicLong nextOrderId = new AtomicLong(200);

    private final UserClient userClient;
    private final PaymentClient paymentClient;

    public OrderService(UserClient userClient, PaymentClient paymentClient) {
        this.userClient = userClient;
        this.paymentClient = paymentClient;
        seed(new Order(100L, 1L, "Standing Desk", new BigDecimal("2490.00"), STATUS_PAID), "seed-100");
        seed(new Order(101L, 2L, "Mechanical Keyboard", new BigDecimal("899.00"), STATUS_PAID), "seed-101");
        seed(new Order(102L, 3L, "Monitor Arm", new BigDecimal("599.00"), STATUS_PAID), "seed-102");
        seed(new Order(103L, 999L, "Laptop Stand", new BigDecimal("349.00"), STATUS_PAID), "seed-103");
    }

    private void seed(Order order, String paymentId) {
        order.setPaymentId(paymentId);
        orders.put(order.getId(), order);
    }


    public OrderResponse getOrderWithUser(Long orderId) {
        Order order = orders.get(orderId);
        if (order == null) {
            throw new OrderNotFoundException(orderId);
        }

        UserServiceUser user = userClient.getUser(order.getUserId());
        return toResponse(order, new CustomerSummary(user.id(), user.name(), user.email()));
    }


    public OrderResponse createOrder(CreateOrderRequest request) {
        validate(request);

        UserServiceUser user = userClient.getUser(request.userId());

        Long orderId = nextOrderId.getAndIncrement();
        Order order = new Order(orderId, user.id(), request.product(), request.amount(),
                STATUS_PENDING_PAYMENT);
        orders.put(orderId, order);

        CustomerSummary customer = new CustomerSummary(user.id(), user.name(), user.email());
        String idempotencyKey = "order-" + orderId;

        try {
            PaymentServicePayment payment = paymentClient.charge(orderId, request.amount(), idempotencyKey);
            order.setStatus(STATUS_PAID);
            order.setPaymentId(payment.paymentId());
            log.info("Order {} paid with payment {}", orderId, payment.paymentId());

        } catch (PaymentDeclinedException ex) {
            order.setStatus(STATUS_PAYMENT_DECLINED);
            log.info("Order {} recorded as declined", orderId);
            throw ex;

        } catch (PaymentException ex) {
            order.setStatus(STATUS_PENDING_PAYMENT);
            log.warn("Order {} recorded without payment ({}): {}", orderId, ex.getReason(), ex.getMessage());
        }

        return toResponse(order, customer);
    }

    private void validate(CreateOrderRequest request) {
        if (request.userId() == null) {
            throw new IllegalArgumentException("'userId' is required");
        }
        if (request.product() == null || request.product().isBlank()) {
            throw new IllegalArgumentException("'product' is required");
        }
        if (request.amount() == null || request.amount().signum() <= 0) {
            throw new IllegalArgumentException("'amount' must be greater than zero");
        }
    }

    private OrderResponse toResponse(Order order, CustomerSummary customer) {
        return new OrderResponse(order.getId(), order.getProduct(), order.getAmount(),
                order.getStatus(), customer, paymentSummary(order));
    }

    private PaymentSummary paymentSummary(Order order) {
        return switch (order.getStatus()) {
            case STATUS_PAID -> new PaymentSummary("COMPLETED", order.getPaymentId(), null);
            case STATUS_PAYMENT_DECLINED -> new PaymentSummary("DECLINED", null,
                    "Payment was declined. This order will not be fulfilled.");
            default -> new PaymentSummary("NOT_COMPLETED", null,
                    "Payment has not been completed. The order is recorded but will not be fulfilled until payment succeeds.");
        };
    }
}
