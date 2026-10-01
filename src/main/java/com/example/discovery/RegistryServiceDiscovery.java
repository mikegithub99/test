package com.example.discovery;
import com.example.registry.grpc.*;
import io.grpc.*;
import java.util.Set;
public final class RegistryServiceDiscovery implements ServiceDiscovery, AutoCloseable {
  private final ManagedChannel channel;
  private final GrpcServiceRegistryGrpc.GrpcServiceRegistryBlockingStub stub;
  public RegistryServiceDiscovery(String host,int port) {
    channel=ManagedChannelBuilder.forAddress(host,port).usePlaintext().build();
    stub=GrpcServiceRegistryGrpc.newBlockingStub(channel);
  }
  public ServiceTarget lookup(String serviceName,Set<String> excluded) {
    LookupResponse r=stub.lookup(LookupRequest.newBuilder().setServiceName(serviceName).addAllExcludedInstanceIds(excluded).build());
    ServiceInstance x=r.getInstance();
    if(x.getServiceName().isBlank()||x.getHost().isBlank()||x.getPort()<=0) throw new IllegalStateException("Invalid registry target");
    return new ServiceTarget(x.getServiceName(),x.getInstanceId(),x.getHost()+":"+x.getPort());
  }
  public void close(){channel.shutdown();}
}