package com.example.discovery;
import io.grpc.ManagedChannel;
public final class GrpcClient<T> {
  private final ServiceTarget target; private final ManagedChannel channel; private final T delegate;
  public GrpcClient(ServiceTarget target,ManagedChannel channel,T delegate){this.target=target;this.channel=channel;this.delegate=delegate;}
  public ServiceTarget target(){return target;} public T delegate(){return delegate;}
  public void retire(){channel.shutdown();}
}