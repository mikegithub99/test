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

/**
 * Spring configuration for the gRPC client adapters and common retry policy.
 *
 * <p>The concrete adapter is created once as a Spring singleton and then
 * wrapped with the retry proxy. The application receives the proxy under the
 * existing {@code customerAdapter} bean name, ensuring that calls use the
 * common retry behavior.</p>
 */
@Configuration
public class GrpcClientConfiguration {

    /**
     * Creates the shared retry policy from application configuration.
     *
     * @param maxAttempts maximum total attempts for one invocation
     * @param delayMillis initial retry delay
     * @param backoffMultiplier exponential backoff multiplier
     * @param maxDelayMillis maximum delay for one retry
     * @param retryableStatuses comma-separated gRPC status names
     * @return configured retry policy
     */
    @Bean
    public GrpcRetryPolicy grpcRetryPolicy(
            @Value("${grpc.retry.max-attempts}") int maxAttempts,
            @Value("${grpc.retry.delay-millis}") long delayMillis,
            @Value("${grpc.retry.backoff-multiplier}") double backoffMultiplier,
            @Value("${grpc.retry.max-delay-millis}") long maxDelayMillis,
            @Value("${grpc.retry.retryable-statuses}") String retryableStatuses) {

        Set<Status.Code> statuses = Arrays.stream(retryableStatuses.split(","))
                .map(String::trim)
                .map(Status.Code::valueOf)
                .collect(Collectors.toSet());

        return new GrpcRetryPolicy(
                maxAttempts,
                delayMillis,
                backoffMultiplier,
                maxDelayMillis,
                statuses);
    }

    /**
     * Creates the application-facing customer adapter bean.
     *
     * <p>The returned object is the retry proxy rather than the raw
     * implementation, so callers cannot bypass the configured retry policy.</p>
     *
     * @param host configured gRPC service host
     * @param port configured gRPC service port
     * @param retryPolicy shared retry policy
     * @return retrying customer adapter
     */
    @Bean(name = "customerAdapter")
    public CustomerAdapterGrpc customerAdapter(
            @Value("${grpc.services.customer.host}") String host,
            @Value("${grpc.services.customer.port}") int port,
            GrpcRetryPolicy retryPolicy) {

        CustomerAdapterGrpcImpl adapter = new CustomerAdapterGrpcImpl(host, port);

        return GrpcRetryProxy.wrap(adapter, CustomerAdapterGrpc.class, retryPolicy);
    }
}
