package com.example.discovery;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.Assert;
import org.junit.Test;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

public class GrpcClientManagerTest {
    @Test public void kubernetesPolicyKeepsSameClientAcrossRetry() {
        final AtomicInteger creates = new AtomicInteger();
        final AtomicInteger calls = new AtomicInteger();
        ServiceDiscovery discovery = (service, excluded) ->
                new ServiceTarget(service, "CUSTOMER_SERVICE",
                        "customer-service.default.svc.cluster.local:50051");

        GrpcClientManager manager = new GrpcClientManager(
                discovery, new KubernetesRecoveryPolicy(), 10, 2);
        try {
            String result = manager.execute("CUSTOMER_SERVICE",
                    channel -> { creates.incrementAndGet(); return new Object(); },
                    client -> {
                        if (calls.getAndIncrement() == 0) {
                            throw new StatusRuntimeException(Status.UNAVAILABLE);
                        }
                        return "ok";
                    });

            Assert.assertEquals("ok", result);
            Assert.assertEquals(1, creates.get());
        } finally {
            manager.close();
        }
    }

    @Test public void registryPolicyReplacesClientAndExcludesFailedInstance() {
        final AtomicInteger creates = new AtomicInteger();
        final AtomicInteger lookups = new AtomicInteger();
        final AtomicInteger calls = new AtomicInteger();

        ServiceDiscovery discovery = (service, excluded) -> {
            int lookup = lookups.getAndIncrement();
            if (lookup == 0) {
                Assert.assertTrue(excluded.isEmpty());
                return new ServiceTarget(service, "instance-1", "server-a:50051");
            }
            Assert.assertTrue(excluded.contains("instance-1"));
            return new ServiceTarget(service, "instance-2", "server-b:50051");
        };

        GrpcClientManager manager = new GrpcClientManager(
                discovery, new RegistryRecoveryPolicy(), 10, 2);
        try {
            String result = manager.execute("CUSTOMER_SERVICE",
                    channel -> { creates.incrementAndGet(); return new Object(); },
                    client -> {
                        if (calls.getAndIncrement() == 0) {
                            throw new StatusRuntimeException(Status.UNAVAILABLE);
                        }
                        return "ok";
                    });

            Assert.assertEquals("ok", result);
            Assert.assertEquals(2, creates.get());
            Assert.assertEquals(2, lookups.get());
        } finally {
            manager.close();
        }
    }
}
