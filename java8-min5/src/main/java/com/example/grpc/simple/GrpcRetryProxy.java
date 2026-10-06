package com.example.grpc.simple;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

public final class GrpcRetryProxy {

    private GrpcRetryProxy() {
    }

    public static <T> T wrap(
            T delegate,
            Class<T> apiType,
            int maxAttempts,
            long retryDelayMillis) {

        InvocationHandler handler = new RetryInvocationHandler(
                delegate,
                maxAttempts,
                retryDelayMillis);

        return apiType.cast(Proxy.newProxyInstance(
                apiType.getClassLoader(),
                new Class<?>[]{apiType},
                handler));
    }

    private static final class RetryInvocationHandler
            implements InvocationHandler {

        private final Object delegate;
        private final int maxAttempts;
        private final long retryDelayMillis;

        private RetryInvocationHandler(
                Object delegate,
                int maxAttempts,
                long retryDelayMillis) {

            if (maxAttempts < 1) {
                throw new IllegalArgumentException(
                        "maxAttempts must be at least 1");
            }

            this.delegate = delegate;
            this.maxAttempts = maxAttempts;
            this.retryDelayMillis = retryDelayMillis;
        }

        @Override
        public Object invoke(
                Object proxy,
                Method method,
                Object[] args) throws Throwable {

            int attempts = 0;

            while (true) {
                attempts++;

                try {
                    return method.invoke(delegate, args);
                } catch (InvocationTargetException e) {
                    Throwable cause = e.getCause();

                    if (!(cause instanceof StatusRuntimeException)
                            || !isRetryable((StatusRuntimeException) cause)
                            || attempts >= maxAttempts) {
                        throw cause;
                    }

                    sleepBeforeRetry();
                }
            }
        }

        private boolean isRetryable(StatusRuntimeException e) {
            Status.Code code = e.getStatus().getCode();

            return code == Status.Code.UNAVAILABLE
                    || code == Status.Code.DEADLINE_EXCEEDED
                    || code == Status.Code.RESOURCE_EXHAUSTED;
        }

        private void sleepBeforeRetry() {
            if (retryDelayMillis <= 0) {
                return;
            }

            try {
                Thread.sleep(retryDelayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(
                        "Interrupted during gRPC retry delay", e);
            }
        }
    }
}
