package com.example.grpc.simple;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GrpcClientConfiguration {

    @Bean
    public ServiceEndpointProvider serviceEndpointProvider(
            @Value("${grpc.customer.host}") String host,
            @Value("${grpc.customer.port}") int port) {

        return new StaticServiceEndpointProvider(
                "customer-service", host, port);
    }

    @Bean
    public CustomerApi customerApi(
            ServiceEndpointProvider endpointProvider,
            @Value("${grpc.client.max-attempts:3}") int maxAttempts,
            @Value("${grpc.client.retry-delay-millis:100}") long retryDelayMillis) {

        return new CustomerAdapterGrpc(
                endpointProvider,
                maxAttempts,
                retryDelayMillis);
    }
}
