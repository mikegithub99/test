package com.example.grpc.simple;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Objects;

public final class GrpcApiProxyFactory {
    private GrpcApiProxyFactory() {
    }

    public static <A, C> A create(
            Class<A> apiType,
            String serviceName,
            GrpcClientManager manager,
            GrpcClientFactory<C> factory) {

        Objects.requireNonNull(apiType, "apiType");
        Objects.requireNonNull(serviceName, "serviceName");
        Objects.requireNonNull(manager, "manager");
        Objects.requireNonNull(factory, "factory");

        InvocationHandler handler = new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) {
                if (method.getDeclaringClass() == Object.class) {
                    if ("toString".equals(method.getName())) {
                        return apiType.getSimpleName() + "GrpcProxy";
                    }
                    if ("hashCode".equals(method.getName())) {
                        return System.identityHashCode(proxy);
                    }
                    if ("equals".equals(method.getName())) {
                        return proxy == args[0];
                    }
                }

                return manager.execute(serviceName, factory, new java.util.function.Function<C, Object>() {
                    @Override
                    public Object apply(C client) {
                        return invokeClient(client, method, args);
                    }
                });
            }
        };

        return apiType.cast(Proxy.newProxyInstance(
                apiType.getClassLoader(),
                new Class<?>[] { apiType },
                handler));
    }

    private static Object invokeClient(Object client, Method method, Object[] args) {
        try {
            return method.invoke(client, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw new IllegalStateException("gRPC client method failed", cause);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot invoke gRPC client method", e);
        }
    }
}
