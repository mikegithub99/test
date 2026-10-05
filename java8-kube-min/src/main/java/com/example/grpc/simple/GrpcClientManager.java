package com.example.grpc.simple;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

public class GrpcClientManager {
    private final ServiceEndpointProvider endpointProvider;
    private final int maxAttempts;
    private final long retryDelayMillis;
    private final Map<String, Object> clients = new ConcurrentHashMap<String, Object>();

    public GrpcClientManager(ServiceEndpointProvider endpointProvider, int maxAttempts, long retryDelayMillis) {
        this.endpointProvider = endpointProvider;
        this.maxAttempts = maxAttempts;
        this.retryDelayMillis = retryDelayMillis;
    }

    @SuppressWarnings("unchecked")
    public <T> T getClient(String serviceName, Function<ManagedChannel, T> stubFactory) {
        Object existing = clients.get(serviceName);
        if (existing != null) return (T) existing;

        ServiceEndpoint endpoint = endpointProvider.getEndpoint(serviceName);
        ManagedChannel channel = ManagedChannelBuilder.forAddress(endpoint.getHost(), endpoint.getPort())
                .usePlaintext().build();
        T client = stubFactory.apply(channel);
        Object previous = clients.putIfAbsent(serviceName, client);
        if (previous != null) {
            channel.shutdown();
            return (T) previous;
        }
        return client;
    }

    public <T> T call(String serviceName, Function<T, T> operation) {
        throw new UnsupportedOperationException("Use generated gRPC stub call with retry in the adapter.");
    }

    public boolean isRetryable(StatusRuntimeException e) {
        Status.Code code = e.getStatus().getCode();
        return code == Status.Code.UNAVAILABLE
                || code == Status.Code.DEADLINE_EXCEEDED
                || code == Status.Code.RESOURCE_EXHAUSTED;
    }

    public int getMaxAttempts() { return maxAttempts; }
    public long getRetryDelayMillis() { return retryDelayMillis; }
}