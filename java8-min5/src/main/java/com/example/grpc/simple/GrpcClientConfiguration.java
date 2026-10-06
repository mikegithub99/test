package com.example.grpc.simple;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GrpcClientConfiguration {

    @Bean
    public CustomerApi customerApi(
            @Value("${grpc.customer.host}") String host,
            @Value("${grpc.customer.port}") int port,
            @Value("${grpc.client.max-attempts:3}") int maxAttempts,
            @Value("${grpc.client.retry-delay-millis:100}") long retryDelayMillis) {

        CustomerAdapterGrpc adapter =
                new CustomerAdapterGrpc(host, port);

        return GrpcRetryProxy.wrap(
                adapter,
                CustomerApi.class,
                maxAttempts,
                retryDelayMillis);
    }
}
