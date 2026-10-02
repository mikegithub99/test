package com.example.grpc.simple;
import io.grpc.ManagedChannel;
@FunctionalInterface public interface GrpcClientFactory<T> { T create(ManagedChannel channel); }