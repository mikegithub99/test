package com.example.grpc.simple.config;

import com.example.grpc.simple.CustomerAdapterGrpc;
import com.example.grpc.simple.CustomerApi;
import com.example.grpc.simple.GrpcRetryPolicy;
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
            @Value("${grpc.retry.max-attempts}") int maxAttempts,
            @Value("${grpc.retry.delay-millis}") long delayMillis,
            @Value("${grpc.retry.retryable-statuses}") String retryableStatuses) {

        Set<Status.Code> statuses = Arrays.stream(retryableStatuses.split(","))
                .map(String::trim)
                .map(Status.Code::valueOf)
                .collect(Collectors.toSet());

        return new GrpcRetryPolicy(
                maxAttempts,
                delayMillis,
                statuses);
    }

    @Bean
    public CustomerApi customerApi(
            @Value("${grpc.services.customer.host}") String host,
            @Value("${grpc.services.customer.port}") int port,
            GrpcRetryPolicy retryPolicy) {

        CustomerAdapterGrpc adapter =
                new CustomerAdapterGrpc(host, port);

        return com.example.grpc.simple.GrpcRetryProxy.wrap(
                adapter,
                CustomerApi.class,
                retryPolicy);
    }
}
