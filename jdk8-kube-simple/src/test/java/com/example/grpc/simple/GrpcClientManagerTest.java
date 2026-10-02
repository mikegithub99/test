package com.example.grpc.simple;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GrpcClientManagerTest {
    @Test
    void retriesOnSameClient() {
        AtomicInteger creates = new AtomicInteger();
        AtomicInteger calls = new AtomicInteger();

        GrpcClientManager manager = new GrpcClientManager(
                new ServiceEndpointProvider() {
                    @Override
                    public ServiceEndpoint endpoint(String name) {
                        return ServiceEndpoint.hostPort(name, "customer-service", 50051);
                    }
                },
                3,
                Duration.ZERO);

        try {
            String result = manager.execute(
                    "CUSTOMER_SERVICE",
                    new GrpcClientFactory<Object>() {
                        @Override
                        public Object create(io.grpc.ManagedChannel channel) {
                            creates.incrementAndGet();
                            return new Object();
                        }
                    },
                    new java.util.function.Function<Object, String>() {
                        @Override
                        public String apply(Object client) {
                            if (calls.getAndIncrement() < 2) {
                                throw new StatusRuntimeException(Status.UNAVAILABLE);
                            }
                            return "ok";
                        }
                    });

            assertEquals("ok", result);
            assertEquals(1, creates.get());
            assertEquals(3, calls.get());
        } finally {
            manager.close();
        }
    }

    @Test
    void doesNotRetryNonRetryable() {
        AtomicInteger calls = new AtomicInteger();

        GrpcClientManager manager = new GrpcClientManager(
                new ServiceEndpointProvider() {
                    @Override
                    public ServiceEndpoint endpoint(String name) {
                        return ServiceEndpoint.hostPort(name, "server-a", 50051);
                    }
                },
                3,
                Duration.ZERO);

        try {
            assertThrows(StatusRuntimeException.class, () ->
                    manager.execute(
                            "CUSTOMER_SERVICE",
                            new GrpcClientFactory<Object>() {
                                @Override
                                public Object create(io.grpc.ManagedChannel channel) {
                                    return new Object();
                                }
                            },
                            new java.util.function.Function<Object, Object>() {
                                @Override
                                public Object apply(Object client) {
                                    calls.incrementAndGet();
                                    throw new StatusRuntimeException(Status.INVALID_ARGUMENT);
                                }
                            }));

            assertEquals(1, calls.get());
        } finally {
            manager.close();
        }
    }
}
