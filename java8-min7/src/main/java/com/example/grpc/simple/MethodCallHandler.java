package com.example.grpc.simple;

@FunctionalInterface
public interface MethodCallHandler {
    Object invoke(Object proxy, Object[] args) throws Throwable;
}
