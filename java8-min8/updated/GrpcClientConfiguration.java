package com.example.grpc.simple.config;

import com.example.grpc.simple.CustomerAdapterGrpc;
import com.example.grpc.simple.CustomerAdapterGrpcImpl;
import com.example.grpc.simple.GrpcRetryPolicy;
import com.example.grpc.simple.GrpcRetryProxy;
import io.grpc.Status;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

@Configuration
public class GrpcClientConfiguration {

    @Bean
    public GrpcRetryPolicy grpcRetryPolicy(
            @Value("\${grpc.retry.max-attempts}") int maxAttempts,
            @Value("\${grpc.retry.delay-millis}") long delayMillis,
            @Value("\${grpc.retry.backoff-multiplier}") double backoffMultiplier,
            @Value("\${grpc.retry.max-delay-millis}") long maxDelayMillis,
            @Value("\${grpc.retry.retryable-statuses}") String retryableStatuses) {

        Set<Status.Code> statuses = Arrays.stream(retryableStatuses.split(","))
                .map(String::trim)
                .map(Status.Code::valueOf)
                .collect(Collectors.toSet());

        return new GrpcRetryPolicy(maxAttempts, delayMillis, backoffMultiplier, maxDelayMillis, statuses);
    }

    @Bean(name = "customerAdapter")
    public CustomerAdapterGrpc customerAdapter(
            @Value("\${grpc.services.customer.host}") String host,
            @Value("\${grpc.services.customer.port}") int port,
            GrpcRetryPolicy retryPolicy) {

        CustomerAdapterGrpcImpl adapter = new CustomerAdapterGrpcImpl(host, port);

        return GrpcRetryProxy.wrap(adapter, CustomerAdapterGrpc.class, retryPolicy);
    }
}
