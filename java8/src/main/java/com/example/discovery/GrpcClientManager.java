package com.example.discovery;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.RemovalListener;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

public final class GrpcClientManager implements AutoCloseable {
    private final ServiceDiscovery discovery;
    private final Cache<String, GrpcClient> cache;
    private final ConcurrentMap<String, GrpcClient> active =
            new ConcurrentHashMap<String, GrpcClient>();
    private final ConcurrentMap<String, Object> locks =
            new ConcurrentHashMap<String, Object>();

    public GrpcClientManager(ServiceDiscovery discovery, long maxSize) {
        this.discovery = discovery;
        this.cache = CacheBuilder.newBuilder()
                .maximumSize(maxSize)
                .removalListener(
                        (RemovalListener<String, GrpcClient>) notification -> {
                            if (notification.getValue() != null) {
                                notification.getValue().retire();
                            }
                        })
                .build();
    }

    public <T, R> R execute(
            String serviceName,
            GrpcClientFactory<T> factory,
            Function<T, R> operation) {

        for (;;) {
            GrpcClient client = getOrResolve(serviceName, factory);

            try {
                return operation.apply(client.<T>delegate());
            } catch (StatusRuntimeException e) {
                if (!retryable(e.getStatus().getCode())) {
                    throw e;
                }

                active.remove(serviceName, client);
                cache.invalidate(client.target().cacheKey());

                replace(
                        serviceName,
                        factory,
                        client.target().instanceId());
            }
        }
    }

    private <T> GrpcClient getOrResolve(
            String serviceName,
            GrpcClientFactory<T> factory) {

        GrpcClient client = active.get(serviceName);
        if (client != null) {
            return client;
        }

        Object lock = locks.get(serviceName);
        if (lock == null) {
            Object created = new Object();
            Object existing = locks.putIfAbsent(serviceName, created);
            lock = existing == null ? created : existing;
        }

        synchronized (lock) {
            client = active.get(serviceName);

            if (client == null) {
                client = create(
                        discovery.lookup(
                                serviceName,
                                Collections.<String>emptySet()),
                        factory);
                active.put(serviceName, client);
            }

            return client;
        }
    }

    private <T> void replace(
            String serviceName,
            GrpcClientFactory<T> factory,
            String failedInstanceId) {

        Object lock = locks.get(serviceName);
        if (lock == null) {
            Object created = new Object();
            Object existing = locks.putIfAbsent(serviceName, created);
            lock = existing == null ? created : existing;
        }

        synchronized (lock) {
            GrpcClient current = active.get(serviceName);

            if (current != null
                    && !Objects.equals(
                            current.target().instanceId(),
                            failedInstanceId)) {
                return;
            }

            active.put(
                    serviceName,
                    create(
                            discovery.lookup(
                                    serviceName,
                                    Collections.singleton(failedInstanceId)),
                            factory));
        }
    }

    private <T> GrpcClient create(
            ServiceTarget target,
            GrpcClientFactory<T> factory) {

        ManagedChannel channel = ManagedChannelBuilder
                .forTarget(target.target())
                .usePlaintext()
                .build();

        GrpcClient client = new GrpcClient(
                target,
                channel,
                factory.create(channel));

        cache.put(target.cacheKey(), client);
        return client;
    }

    private static boolean retryable(Status.Code code) {
        return code == Status.Code.UNAVAILABLE
                || code == Status.Code.DEADLINE_EXCEEDED
                || code == Status.Code.RESOURCE_EXHAUSTED;
    }

    @Override
    public void close() {
        active.values().forEach(GrpcClient::retire);
        active.clear();
        cache.invalidateAll();
        cache.cleanUp();
    }
}
