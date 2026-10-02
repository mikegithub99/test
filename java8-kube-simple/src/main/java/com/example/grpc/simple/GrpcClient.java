package com.example.grpc.simple;
import io.grpc.ManagedChannel;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
public final class GrpcClient implements AutoCloseable {
    private final ServiceEndpoint endpoint; private final ManagedChannel channel; private final Object delegate;
    public GrpcClient(ServiceEndpoint endpoint, ManagedChannel channel, Object delegate) { this.endpoint=Objects.requireNonNull(endpoint); this.channel=Objects.requireNonNull(channel); this.delegate=Objects.requireNonNull(delegate); }
    public ServiceEndpoint endpoint(){return endpoint;} public ManagedChannel channel(){return channel;}
    @SuppressWarnings("unchecked") public <T> T delegate(){return (T)delegate;}
    public void close(){ channel.shutdown(); try { if(!channel.awaitTermination(5,TimeUnit.SECONDS)) channel.shutdownNow(); } catch(InterruptedException e){Thread.currentThread().interrupt();channel.shutdownNow();} }
}