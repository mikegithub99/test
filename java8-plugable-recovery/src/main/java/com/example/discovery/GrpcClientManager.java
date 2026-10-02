package com.example.discovery;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.RemovalListener;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

public final class GrpcClientManager implements AutoCloseable {
    private final ServiceDiscovery discovery;
    private final RecoveryPolicy recoveryPolicy;
    private final Cache<String, GrpcClient> activeClients;
    private final ConcurrentMap<String, Object> locks = new ConcurrentHashMap<String, Object>();
    private final int maxAttempts;

    public GrpcClientManager(ServiceDiscovery discovery, RecoveryPolicy recoveryPolicy, long maxSize, int maxAttempts) {
        if (discovery == null) throw new IllegalArgumentException("discovery must not be null");
        if (recoveryPolicy == null) throw new IllegalArgumentException("recoveryPolicy must not be null");
        if (maxSize <= 0 || maxAttempts <= 0) throw new IllegalArgumentException("maxSize and maxAttempts must be positive");
        this.discovery = discovery;
        this.recoveryPolicy = recoveryPolicy;
        this.maxAttempts = maxAttempts;
        this.activeClients = CacheBuilder.newBuilder().maximumSize(maxSize)
                .removalListener((RemovalListener<String, GrpcClient>) n -> {
                    GrpcClient client = n.getValue();
                    if (client != null) client.retire();
                }).build();
    }

    public <T, R> R execute(String serviceName, GrpcClientFactory<T> factory, Function<T, R> operation) {
        if (serviceName == null || serviceName.trim().isEmpty()) throw new IllegalArgumentException("serviceName must not be blank");
        if (factory == null || operation == null) throw new IllegalArgumentException("factory and operation are required");
        Set<String> excluded = new HashSet<String>();

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            GrpcClient client = getOrResolve(serviceName, factory);
            if (!client.tryAcquire()) continue;
            try {
                return operation.apply(client.<T>delegate());
            } catch (StatusRuntimeException e) {
                RecoveryAction action = recoveryPolicy.decide(
                        new RecoveryContext(serviceName, client, e, attempt, maxAttempts));
                if (action == RecoveryAction.FAIL || attempt == maxAttempts) throw e;
                if (action == RecoveryAction.REPLACE_CLIENT) {
                    String failedId = client.target().instanceId();
                    if (failedId != null && !failedId.trim().isEmpty()) excluded.add(failedId);
                    replaceIfCurrent(serviceName, client, factory, excluded);
                }
            } finally {
                client.release();
            }
        }
        throw new IllegalStateException("Retry limit reached for " + serviceName);
    }

    private <T> GrpcClient getOrResolve(String serviceName, GrpcClientFactory<T> factory) {
        GrpcClient client = activeClients.getIfPresent(serviceName);
        if (client != null && !client.isRetired()) return client;
        Object lock = lockFor(serviceName);
        synchronized (lock) {
            client = activeClients.getIfPresent(serviceName);
            if (client != null && !client.isRetired()) return client;
            if (client != null) activeClients.invalidate(serviceName);
            GrpcClient created = create(discovery.lookup(serviceName, Collections.<String>emptySet()), factory);
            activeClients.put(serviceName, created);
            return created;
        }
    }

    private <T> void replaceIfCurrent(String serviceName, GrpcClient failedClient, GrpcClientFactory<T> factory, Set<String> excluded) {
        Object lock = lockFor(serviceName);
        synchronized (lock) {
            GrpcClient current = activeClients.getIfPresent(serviceName);
            if (current != failedClient) return;
            activeClients.invalidate(serviceName);
            ServiceTarget next = discovery.lookup(serviceName,
                    Collections.unmodifiableSet(new HashSet<String>(excluded)));
            activeClients.put(serviceName, create(next, factory));
        }
    }

    private Object lockFor(String serviceName) {
        Object lock = locks.get(serviceName);
        if (lock == null) {
            Object created = new Object();
            Object existing = locks.putIfAbsent(serviceName, created);
            lock = existing == null ? created : existing;
        }
        return lock;
    }

    private <T> GrpcClient create(ServiceTarget target, GrpcClientFactory<T> factory) {
        if (target == null) throw new IllegalStateException("Discovery returned null target");
        ManagedChannel channel = ManagedChannelBuilder.forTarget(target.target()).usePlaintext().build();
        try {
            return new GrpcClient(target, channel, factory.create(channel));
        } catch (RuntimeException e) {
            channel.shutdown();
            throw e;
        }
    }

    @Override public void close() {
        activeClients.invalidateAll();
        activeClients.cleanUp();
        locks.clear();
    }
}
