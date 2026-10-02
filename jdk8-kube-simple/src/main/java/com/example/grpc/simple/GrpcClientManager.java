package com.example.grpc.simple;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

public final class GrpcClientManager implements AutoCloseable {
    private final ServiceEndpointProvider endpoints;
    private final int maxAttempts;
    private final Duration retryDelay;
    private final ConcurrentMap<String, ClientHolder<?>> clients =
            new ConcurrentHashMap<String, ClientHolder<?>>();

    public GrpcClientManager(ServiceEndpointProvider endpoints, int maxAttempts, Duration retryDelay) {
        this.endpoints = Objects.requireNonNull(endpoints, "endpoints");
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }
        this.maxAttempts = maxAttempts;
        this.retryDelay = Objects.requireNonNull(retryDelay, "retryDelay");
        if (retryDelay.isNegative()) {
            throw new IllegalArgumentException("retryDelay must not be negative");
        }
    }

    public <T, R> R execute(
            String serviceName,
            GrpcClientFactory<T> factory,
            Function<T, R> operation) {

        Objects.requireNonNull(factory, "factory");
        Objects.requireNonNull(operation, "operation");

        ClientHolder<T> client = clientFor(serviceName, factory);

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return operation.apply(client.delegate);
            } catch (StatusRuntimeException e) {
                if (!retryable(e) || attempt == maxAttempts) {
                    throw e;
                }
                sleep();
            }
        }

        throw new IllegalStateException("Unreachable");
    }

    @SuppressWarnings("unchecked")
    private <T> ClientHolder<T> clientFor(String serviceName, GrpcClientFactory<T> factory) {
        if (isBlank(serviceName)) {
            throw new IllegalArgumentException("serviceName must not be blank");
        }

        ClientHolder<?> existing = clients.get(serviceName);
        if (existing != null) {
            return (ClientHolder<T>) existing;
        }

        ServiceEndpoint endpoint = endpoints.endpoint(serviceName);
        ManagedChannel channel = ManagedChannelBuilder.forTarget(endpoint.getTarget())
                .usePlaintext()
                .build();

        ClientHolder<T> created;
        try {
            created = new ClientHolder<T>(endpoint, channel, factory.create(channel));
        } catch (RuntimeException e) {
            channel.shutdownNow();
            throw e;
        }

        ClientHolder<?> winner = clients.putIfAbsent(serviceName, created);
        if (winner != null) {
            created.close();
            return (ClientHolder<T>) winner;
        }
        return created;
    }

    private boolean retryable(StatusRuntimeException e) {
        Status.Code code = e.getStatus().getCode();
        return code == Status.Code.UNAVAILABLE
                || code == Status.Code.DEADLINE_EXCEEDED
                || code == Status.Code.RESOURCE_EXHAUSTED;
    }

    private void sleep() {
        if (retryDelay.isZero()) {
            return;
        }
        try {
            Thread.sleep(retryDelay.toMillis(), retryDelay.getNano() % 1000000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting to retry gRPC call", e);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    @Override
    public void close() {
        for (ClientHolder<?> client : clients.values()) {
            client.close();
        }
        clients.clear();
    }

    private static final class ClientHolder<T> {
        private final ServiceEndpoint endpoint;
        private final ManagedChannel channel;
        private final T delegate;

        private ClientHolder(ServiceEndpoint endpoint, ManagedChannel channel, T delegate) {
            this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
            this.channel = Objects.requireNonNull(channel, "channel");
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        private void close() {
            channel.shutdown();
            try {
                if (!channel.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    channel.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                channel.shutdownNow();
            }
        }
    }
}
