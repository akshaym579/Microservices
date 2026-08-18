package com.oneenterprise.orderservice.client;

import com.oneenterprise.orderservice.exception.PaymentCircuitOpenException;
import com.oneenterprise.orderservice.exception.PaymentDeclinedException;
import com.oneenterprise.orderservice.exception.PaymentException;
import com.oneenterprise.orderservice.exception.PaymentUnavailableException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.net.SocketTimeoutException;

@Component
public class PaymentClient {

    private static final Logger log = LoggerFactory.getLogger(PaymentClient.class);

    private final RestClient restClient;
    private final String baseUrl;

    public PaymentClient(RestClient paymentRestClient,
                         @Value("${payment-service.base-url}") String baseUrl) {
        this.restClient = paymentRestClient;
        this.baseUrl = baseUrl;
    }

    @Retry(name = "paymentService")
    @CircuitBreaker(name = "paymentService", fallbackMethod = "circuitOpen")
    public PaymentServicePayment charge(Long orderId, BigDecimal amount, String idempotencyKey) {
        log.info("Charging order {} via {}/api/payments", orderId, baseUrl);

        try {
            PaymentServicePayment payment = restClient.post()
                    .uri("/api/payments")
                    .header("Idempotency-Key", idempotencyKey)
                    .body(new PaymentChargeRequest(orderId, amount))
                    .retrieve()
                    .body(PaymentServicePayment.class);

            if (payment == null) {
                throw new PaymentUnavailableException(PaymentException.Reason.PAYMENT_SERVICE_ERROR,
                        "Payment Service returned an empty response for order " + orderId);
            }
            return payment;

        } catch (HttpServerErrorException ex) {
            log.warn("Payment Service returned {} for order {}", ex.getStatusCode(), orderId);
            throw new PaymentUnavailableException(PaymentException.Reason.PAYMENT_SERVICE_UNAVAILABLE,
                    "Payment Service returned " + ex.getStatusCode() + " while charging order " + orderId);

        } catch (HttpClientErrorException ex) {
            if (ex.getStatusCode().value() == HttpStatus.PAYMENT_REQUIRED.value()) {
                log.info("Payment Service declined order {}", orderId);
                throw new PaymentDeclinedException("Payment for order " + orderId + " was declined");
            }
            log.warn("Payment Service rejected the request for order {} with {}", orderId, ex.getStatusCode());
            throw new PaymentException(PaymentException.Reason.PAYMENT_SERVICE_ERROR,
                    "Payment Service rejected the charge request for order " + orderId);

        } catch (ResourceAccessException ex) {
            if (ex.getMostSpecificCause() instanceof SocketTimeoutException) {
                log.warn("Payment Service did not respond in time for order {}", orderId);
                throw new PaymentUnavailableException(PaymentException.Reason.PAYMENT_SERVICE_TIMEOUT,
                        "Payment Service did not respond in time while charging order " + orderId);
            }
            log.warn("Payment Service is not reachable at {} ({})", baseUrl, ex.getMostSpecificCause().toString());
            throw new PaymentUnavailableException(PaymentException.Reason.PAYMENT_SERVICE_UNAVAILABLE,
                    "Payment Service is not reachable at " + baseUrl);

        } catch (RestClientException ex) {
            log.warn("Unexpected failure charging order {}", orderId, ex);
            throw new PaymentUnavailableException(PaymentException.Reason.PAYMENT_SERVICE_ERROR,
                    "Call to Payment Service failed while charging order " + orderId);

        } catch (IllegalStateException ex) {
            log.warn("No PAYMENT-SERVICE instance is registered ({})", ex.getMessage());
            throw new PaymentUnavailableException(PaymentException.Reason.PAYMENT_SERVICE_UNAVAILABLE,
                    "No Payment Service instance is currently registered with the discovery server");
        }
    }

    private PaymentServicePayment circuitOpen(Long orderId, BigDecimal amount, String idempotencyKey,
                                              CallNotPermittedException ex) {
        log.warn("Circuit is open, not calling Payment Service for order {}", orderId);
        throw new PaymentCircuitOpenException(
                "Payment Service is not being called right now because it is failing repeatedly");
    }
}
