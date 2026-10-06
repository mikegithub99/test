package com.example.grpc.simple;

@FunctionalInterface
public interface MethodCallInterceptor {
    MethodCallHandler intercept(
            Object proxy,
            java.lang.reflect.Method method,
            Object[] args,
            MethodCallHandler next) throws Throwable;
}
