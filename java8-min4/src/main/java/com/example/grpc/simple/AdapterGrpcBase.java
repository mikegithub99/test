package com.example.grpc.simple;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;

import java.util.function.Function;
import java.util.function.Supplier;

public abstract class AdapterGrpcBase<T> {
    private final T service;
    private final int maxAttempts;
    private final long retryDelayMillis;

    protected AdapterGrpcBase(
            ServiceEndpointProvider endpointProvider,
            String serviceName,
            Function<ManagedChannel, T> stubFactory,
            int maxAttempts,
            long retryDelayMillis) {

        ServiceEndpoint endpoint = endpointProvider.getEndpoint(serviceName);

        ManagedChannel channel = ManagedChannelBuilder
                .forAddress(endpoint.getHost(), endpoint.getPort())
                .usePlaintext()
                .build();

        this.service = stubFactory.apply(channel);
        this.maxAttempts = maxAttempts;
        this.retryDelayMillis = retryDelayMillis;
    }

    protected T getService() {
        return service;
    }

    protected <R> R execute(Supplier<R> operation) {
        int attempts = 0;

        while (true) {
            attempts++;

            try {
                return operation.get();
            } catch (StatusRuntimeException e) {
                if (!isRetryable(e) || attempts >= maxAttempts) {
                    throw e;
                }

                if (retryDelayMillis > 0) {
                    try {
                        Thread.sleep(retryDelayMillis);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(
                                "Interrupted during gRPC retry delay", ie);
                    }
                }
            }
        }
    }

    private boolean isRetryable(StatusRuntimeException e) {
        Status.Code code = e.getStatus().getCode();

        return code == Status.Code.UNAVAILABLE
                || code == Status.Code.DEADLINE_EXCEEDED
                || code == Status.Code.RESOURCE_EXHAUSTED;
    }
}
