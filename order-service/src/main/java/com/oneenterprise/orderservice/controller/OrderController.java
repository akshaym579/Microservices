package com.oneenterprise.orderservice.controller;

import com.oneenterprise.orderservice.dto.CreateOrderRequest;
import com.oneenterprise.orderservice.dto.OrderResponse;
import com.oneenterprise.orderservice.service.OrderService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }


    @GetMapping("/{id}")
    public ResponseEntity<OrderResponse> getOrder(@PathVariable Long id) {
        return ResponseEntity.ok(orderService.getOrderWithUser(id));
    }

    @PostMapping
    public ResponseEntity<OrderResponse> createOrder(@RequestBody CreateOrderRequest request) {
        OrderResponse order = orderService.createOrder(request);
        URI location = URI.create("/api/orders/" + order.orderId());

        HttpStatus status = OrderService.STATUS_PAID.equals(order.status())
                ? HttpStatus.CREATED
                : HttpStatus.ACCEPTED;

        return ResponseEntity.status(status).location(location).body(order);
    }
}
