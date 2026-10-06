package com.example.grpc.simple;

import io.grpc.StatusRuntimeException;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

public final class GrpcRetryProxy {
    private GrpcRetryProxy() {
    }

    public static <T> T wrap(T delegate, Class<T> apiType, GrpcRetryPolicy policy) {
        InvocationHandler handler = new RetryInvocationHandler(delegate, policy);
        return apiType.cast(Proxy.newProxyInstance(
                apiType.getClassLoader(),
                new Class<?>[]{apiType},
                handler));
    }

    private static final class RetryInvocationHandler implements InvocationHandler {
        private final Object delegate;
        private final GrpcRetryPolicy policy;

        private RetryInvocationHandler(Object delegate, GrpcRetryPolicy policy) {
            this.delegate = delegate;
            this.policy = policy;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            int attempts = 0;

            while (true) {
                attempts++;
                try {
                    return method.invoke(delegate, args);
                } catch (InvocationTargetException e) {
                    Throwable cause = e.getCause();
                    if (!(cause instanceof StatusRuntimeException)) {
                        throw cause;
                    }

                    StatusRuntimeException grpcFailure = (StatusRuntimeException) cause;
                    if (!policy.isRetryable(grpcFailure.getStatus().getCode())
                            || attempts >= policy.getMaxAttempts()) {
                        throw grpcFailure;
                    }

                    sleep(policy.getDelayMillis());
                }
            }
        }

        private void sleep(long delayMillis) {
            if (delayMillis <= 0) {
                return;
            }
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted during gRPC retry delay", e);
            }
        }
    }
}
