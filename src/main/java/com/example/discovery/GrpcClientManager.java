package com.example.discovery;
import com.google.common.cache.*;
import io.grpc.*;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
public final class GrpcClientManager<T> implements AutoCloseable {
  private final ServiceDiscovery discovery; private final GrpcClientFactory<T> factory;
  private final Cache<String,GrpcClient<T>> cache;
  private final ConcurrentMap<String,GrpcClient<T>> active=new ConcurrentHashMap<>();
  private final ConcurrentMap<String,Object> locks=new ConcurrentHashMap<>();
  public GrpcClientManager(ServiceDiscovery d,GrpcClientFactory<T> f,long maxSize){
    discovery=d;factory=f;
    cache=CacheBuilder.newBuilder().maximumSize(maxSize).removalListener((RemovalListener<String,GrpcClient<T>>)n->{if(n.getValue()!=null)n.getValue().retire();}).build();
  }
  public <R> R execute(String service,Function<T,R> op){
    for(;;){
      GrpcClient<T> c=getOrResolve(service,Set.of());
      try{return op.apply(c.delegate());}
      catch(StatusRuntimeException e){
        if(!retryable(e.getStatus().getCode()))throw e;
        active.remove(service,c); cache.invalidate(c.target().cacheKey());
        replace(service,c.target().instanceId());
      }
    }
  }
  private GrpcClient<T> getOrResolve(String service,Set<String> excluded){
    GrpcClient<T> c=active.get(service); if(c!=null)return c;
    synchronized(locks.computeIfAbsent(service,k->new Object())){
      c=active.get(service); if(c==null){c=create(discovery.lookup(service,excluded));active.put(service,c);} return c;
    }
  }
  private void replace(String service,String failedId){
    synchronized(locks.computeIfAbsent(service,k->new Object())){
      GrpcClient<T> c=active.get(service);
      if(c!=null && !java.util.Objects.equals(c.target().instanceId(),failedId))return;
      active.put(service,create(discovery.lookup(service,Set.of(failedId))));
    }
  }
  private GrpcClient<T> create(ServiceTarget t){
    ManagedChannel ch=ManagedChannelBuilder.forTarget(t.target()).usePlaintext().build();
    GrpcClient<T> c=new GrpcClient<>(t,ch,factory.create(ch)); cache.put(t.cacheKey(),c); return c;
  }
  private static boolean retryable(Status.Code c){return c==Status.Code.UNAVAILABLE||c==Status.Code.DEADLINE_EXCEEDED||c==Status.Code.RESOURCE_EXHAUSTED;}
  public void close(){active.values().forEach(GrpcClient::retire);active.clear();cache.invalidateAll();cache.cleanUp();}
}