package com.oneenterprise.orderservice.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

    @Bean
    @LoadBalanced
    public RestClient.Builder loadBalancedRestClientBuilder() {
        return RestClient.builder();
    }

    @Bean
    public RestClient userRestClient(@LoadBalanced RestClient.Builder builder,
                                     @Value("${user-service.base-url}") String baseUrl,
                                     @Value("${user-service.connect-timeout-ms}") int connectTimeoutMs,
                                     @Value("${user-service.read-timeout-ms}") int readTimeoutMs) {
        return builder.clone()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory(connectTimeoutMs, readTimeoutMs))
                .build();
    }

    @Bean
    public RestClient paymentRestClient(@LoadBalanced RestClient.Builder builder,
                                        @Value("${payment-service.base-url}") String baseUrl,
                                        @Value("${payment-service.connect-timeout-ms}") int connectTimeoutMs,
                                        @Value("${payment-service.read-timeout-ms}") int readTimeoutMs) {
        return builder.clone()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory(connectTimeoutMs, readTimeoutMs))
                .build();
    }

    private ClientHttpRequestFactory requestFactory(int connectTimeoutMs, int readTimeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        return factory;
    }
}
