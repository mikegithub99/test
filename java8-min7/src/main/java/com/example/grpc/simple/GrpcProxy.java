package com.example.grpc.simple;

import io.grpc.StatusRuntimeException;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class GrpcProxy {
    private GrpcProxy() {
    }

    public static <T> T create(
            T delegate,
            Class<T> apiType,
            GrpcRetryPolicy retryPolicy) {

        if (delegate == null || apiType == null || retryPolicy == null) {
            throw new NullPointerException("delegate, apiType, and retryPolicy are required");
        }

        final Map<Method, MethodCallHandler> cache = new ConcurrentHashMap<Method, MethodCallHandler>();
        final MethodCallInterceptor retry = retryInterceptor(retryPolicy);

        InvocationHandler invocationHandler = new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                MethodCallHandler handler = cache.get(method);
                if (handler == null) {
                    MethodCallHandler terminal = terminalHandler(delegate, method);
                    handler = retry.intercept(proxy, method, args, terminal);
                    MethodCallHandler previous = cache.putIfAbsent(method, handler);
                    if (previous != null) {
                        handler = previous;
                    }
                }
                return handler.invoke(proxy, args);
            }
        };

        return apiType.cast(Proxy.newProxyInstance(
                apiType.getClassLoader(),
                new Class<?>[]{apiType},
                invocationHandler));
    }

    private static MethodCallHandler terminalHandler(final Object delegate, final Method method) {
        return new MethodCallHandler() {
            @Override
            public Object invoke(Object proxy, Object[] args) throws Throwable {
                try {
                    return method.invoke(delegate, args);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            }
        };
    }

    private static MethodCallInterceptor retryInterceptor(final GrpcRetryPolicy policy) {
        return new MethodCallInterceptor() {
            @Override
            public MethodCallHandler intercept(
                    Object proxy,
                    Method method,
                    Object[] args,
                    final MethodCallHandler next) {

                return new MethodCallHandler() {
                    @Override
                    public Object invoke(Object proxy, Object[] args) throws Throwable {
                        int attempts = 0;
                        while (true) {
                            attempts++;
                            try {
                                return next.invoke(proxy, args);
                            } catch (StatusRuntimeException e) {
                                if (!policy.isRetryable(e.getStatus().getCode())
                                        || attempts >= policy.getMaxAttempts()) {
                                    throw e;
                                }
                                sleep(policy.getDelayMillis());
                            }
                        }
                    }
                };
            }
        };
    }

    private static void sleep(long delayMillis) {
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
