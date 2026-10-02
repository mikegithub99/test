# Java 8 gRPC Client

This folder contains the Java 8-compatible discovery and channel-management implementation.

# Section 1 — Java 8

## 1.1 Generic boundary

Only the client factory is generic:

~~~java
public interface GrpcClientFactory<T> {
    T create(ManagedChannel channel);
}
~~~

`GrpcClientManager` is deliberately not generic. It manages discovery, channels, caching, replacement, failover, and lifecycle without knowing service-specific protobuf types.

## 1.2 Static discovery

Static discovery maps a logical service name to instances:

~~~text
CUSTOMER_SERVICE
    |
    +-- customer-1 -> server-a:50051
    +-- customer-2 -> server-b:50051
    +-- customer-3 -> server-a:50052
~~~

It does not map a protobuf Java type to a host.

## 1.3 Spring `@Bean ServiceDiscovery`

The complete static discovery bean is:

~~~java
@Configuration
public class GrpcDiscoveryConfiguration {

    @Bean
    ServiceDiscovery serviceDiscovery() {
        Map<String, List<ServiceTarget>> services =
                new HashMap<String, List<ServiceTarget>>();

        services.put(
                "CUSTOMER_SERVICE",
                Arrays.asList(
                        new ServiceTarget(
                                "CUSTOMER_SERVICE",
                                "customer-1",
                                "server-a:50051"),
                        new ServiceTarget(
                                "CUSTOMER_SERVICE",
                                "customer-2",
                                "server-b:50051"),
                        new ServiceTarget(
                                "CUSTOMER_SERVICE",
                                "customer-3",
                                "server-a:50052")
                ));

        return new StaticServiceDiscovery(services);
    }

    @Bean(destroyMethod = "close")
    GrpcClientManager grpcClientManager(
            ServiceDiscovery serviceDiscovery) {
        return new GrpcClientManager(
                serviceDiscovery,
                100);
    }
}
~~~

The important first bean is explicitly:

~~~java
@Bean
ServiceDiscovery serviceDiscovery() {
    return new StaticServiceDiscovery(services);
}
~~~

Spring injects that implementation into `GrpcClientManager`.

## 1.4 Customer adapter and `CustomerGrpcClient::new`

The service-specific adapter receives the channel:

~~~java
public final class CustomerGrpcClient
        implements CustomerApi {

    private final CustomerServiceGrpc.CustomerServiceBlockingStub stub;

    public CustomerGrpcClient(ManagedChannel channel) {
        this.stub = CustomerServiceGrpc
                .newBlockingStub(channel);
    }
}
~~~

Therefore:

~~~java
CustomerGrpcClient::new
~~~

is effectively:

~~~java
channel -> new CustomerGrpcClient(channel)
~~~

The manager creates the channel. The adapter creates the generated stub on that channel.

## 1.5 Application-facing proxy

~~~java
public interface CustomerApi {
    String getCustomer(String customerId);
}
~~~

~~~java
@Bean
CustomerApi customerApi(GrpcClientManager manager) {

    InvocationHandler handler = (proxy, method, args) ->
            manager.execute(
                    "CUSTOMER_SERVICE",
                    CustomerGrpcClient::new,
                    client -> method.invoke(client, args));

    return (CustomerApi) Proxy.newProxyInstance(
            CustomerApi.class.getClassLoader(),
            new Class<?>[]{CustomerApi.class},
            handler);
}
~~~

## 1.6 Channel semantics

The current implementation maintains one active client/channel for each logical service:

~~~text
CUSTOMER_SERVICE -> GrpcClient -> ManagedChannel
ORDER_SERVICE    -> GrpcClient -> ManagedChannel
~~~

The channel is **not associated with the Java type**.

If both services happen to use the same endpoint, the current design still keeps their channels separate:

~~~text
CustomerGrpcClient -> ManagedChannel #1 -> server-a:50051
OrderGrpcClient    -> ManagedChannel #2 -> server-a:50051
~~~

Channel sharing can be added later as an explicit transport optimization.

## 1.7 Initial call — channel creation

~~~text
CustomerApi.getCustomer("123")
        |
        v
JDK proxy
        |
        v
GrpcClientManager
        |
        v
StaticServiceDiscovery
        |
        v
customer-1 -> server-a:50051
        |
        v
ManagedChannel #1
        |
        v
CustomerGrpcClient::new(channel1)
        |
        v
generated gRPC stub
        |
        v
RPC
~~~

The resulting client/channel pair is cached for `CUSTOMER_SERVICE`, so subsequent calls reuse it.

## 1.8 Retry — replacement channel

If an RPC returns a configured retryable status such as `UNAVAILABLE`:

~~~text
CustomerGrpcClient #1
        |
ManagedChannel #1
        |
customer-1
        |
UNAVAILABLE
        |
        v
remove active client
        |
        v
invalidate / retire old channel
        |
        v
fresh discovery excluding customer-1
        |
        v
customer-2 -> server-b:50051
        |
        v
ManagedChannel #2
        |
        v
CustomerGrpcClient::new(channel2)
        |
        v
retry original RPC
~~~

The `CustomerApi` proxy remains unchanged. The manager replaces the underlying client/channel pair.

## 1.9 Multiple channels to the same service

The initial implementation intentionally uses one active channel per logical service.

Multiple channels can be useful for very high concurrency, long-lived RPCs, or deliberate connection-level distribution.

Trade-offs include additional connections, memory, keepalive/connection overhead, and more complex lifecycle, failover, metrics, and channel-selection logic.

If a pool is eventually needed, introduce an explicit channel-pool abstraction. Do not make `GrpcClientManager<T>` generic simply to represent multiple channels.

## 1.10 Multiple service types and channel sharing

Two service adapters could explicitly share one channel:

~~~text
server-a:50051
       |
ManagedChannel
    /      \
Customer   Order
 adapter   adapter
~~~

This can reduce connections, but it requires explicit ownership rules so one service cannot retire a channel still used by another. The initial design therefore keeps channel ownership separate.

## 1.11 Registry discovery

The same Java manager can use the external registry:

~~~yaml
grpc:
  discovery:
    mode: REGISTRY
    registry:
      host: registry-host
      port: 9090
~~~

Only the `ServiceDiscovery` implementation changes. The application API, manager, factory, and channel lifecycle remain the same.

## 1.12 Java 8 compatibility

This directory avoids Java 16/17 conveniences used by the newer implementation:

- records
- `Map.copyOf`
- `List.of`
- `Set.of`
- `Stream.toList()`

`ServiceTarget` is a normal final class, and collection creation uses Java 8-compatible APIs.

## 1.13 Files

~~~text
java8/
  javareadme.md
  src/main/java/com/example/discovery/
    ServiceTarget.java
    ServiceDiscovery.java
    StaticServiceDiscovery.java
    RegistryServiceDiscovery.java
    GrpcClient.java
    GrpcClientFactory.java
    GrpcClientManager.java
~~~

These are the Java 8-compatible implementations of all discovery/channel-management classes currently in the repository.

# Java 8 design summary

The Java type does **not** determine the channel.

The logical service name determines what discovery looks up.

The discovered target determines where the channel connects.

The generic factory determines which service-specific adapter is constructed on that channel.

On a retryable failure, the manager replaces the client/channel pair while keeping the application-facing API stable.