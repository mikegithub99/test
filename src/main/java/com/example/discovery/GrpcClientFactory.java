package com.example.discovery;
import io.grpc.ManagedChannel;
@FunctionalInterface public interface GrpcClientFactory<T>{ T create(ManagedChannel channel); }