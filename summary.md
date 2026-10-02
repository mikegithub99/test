# gRPC Service Discovery

A Java 21 / Spring Boot reference implementation for replacing CORBA-style service discovery with gRPC. It supports two discovery modes behind one application-facing client architecture:

- **REGISTRY** — C++ service instances register with the homegrown gRPC registry.
- **STATIC** — the Java application contains a pre-populated service-to-instance map.
- Multiple instances of the same logical service are supported, including different ports on the same host.
- Normal calls reuse a cached gRPC channel.
- On configured retryable gRPC failures, the client performs a fresh discovery lookup and excludes the failed instance before retrying.
- The application talks to a normal Java interface/proxy; callers do not need to know about registry lookups or failover.

## Architecture

```
Spring Boot application
        |
        v
    CustomerApi
        |
        v
   JDK failover proxy
        |
        v
  GrpcClientManager
        |
        +----------------------+
        |                      |
        v                      v
 ServiceDiscovery       cached GrpcClient
        |                      |
   +----+-----+                v
   |          |          ManagedChannel
Registry    Static             |
   |          |                v
   +----+-----+         service-specific adapter
        |
        v
  service instance
```

The important separation is:

- `ServiceDiscovery` answers **where the service is**.
- `ServiceDiscovery` answers **where the service is**.
- `GrpcClientManager` owns cached channels/clients and lifecycle. It is deliberately **not generic** and does not know about `CustomerApi`, `OrderApi`, or generated protobuf stub types.
- `GrpcClientFactory<T>` is the only generic infrastructure type. It converts a `ManagedChannel` into the service-specific adapter, such as `CustomerGrpcClient`.
- The JDK proxy owns **transparent retry/failover behavior**.
- A service-specific adapter owns the generated gRPC stub and maps it to the application API.
- Application code depends only on `CustomerApi` or another application-facing interface.

## Repository layout

```
src/main/proto/registry.proto
    Shared registry protocol

src/main/java/com/example/discovery/
    ServiceDiscovery
    ServiceTarget
    StaticServiceDiscovery
    RegistryServiceDiscovery
    GrpcClient
    GrpcClientFactory
    GrpcClientManager
    CustomerApi
    CustomerGrpcClient

cpp/
    C++17 ServiceProvider
    CMake build
```

## 1. Define the application-facing API

Do not expose the generated gRPC stub to the rest of the application. Define a small interface:

```java
public interface CustomerApi {
    String getCustomer(String customerId);
}
```

The adapter owns the generated gRPC stub:

```java
public final class CustomerGrpcClient implements CustomerApi {

    private final CustomerServiceGrpc.CustomerServiceBlockingStub stub;

    public CustomerGrpcClient(ManagedChannel channel) {
        this.stub = CustomerServiceGrpc.newBlockingStub(channel);
    }

    @Override
    public String getCustomer(String customerId) {
        return stub.getCustomer(
                GetCustomerRequest.newBuilder()
                        .setCustomerId(customerId)
                        .build()
        ).getName();
    }
}
```

This keeps generated protobuf types and transport details out of application code.

## 2. Configure static discovery

Use this when the client application should contain its own service map and no registry is required.

```yaml
grpc:
  discovery:
    mode: STATIC
    services:
      CUSTOMER_SERVICE:
        - instance-id: customer-1
          host: server-a
          port: 50051
        - instance-id: customer-2
          host: server-b
          port: 50051
        - instance-id: customer-3
          host: server-a
          port: 50052
```

The mapping is **logical service -> service instances**. You do not configure a mapping from a Java protobuf stub class to a host.

For example, `CUSTOMER_SERVICE` may be implemented by three C++ processes. The Java adapter knows that `CustomerApi` uses `CustomerServiceGrpc`; discovery only knows where `CUSTOMER_SERVICE` instances are.

## 3. Configure registry discovery

Use this when C++ services register themselves dynamically.

```yaml
grpc:
  discovery:
    mode: REGISTRY
    registry:
      host: registry-host
      port: 9090
```

A C++ service registers:

```text
CUSTOMER_SERVICE / customer-1 -> server-a:50051
CUSTOMER_SERVICE / customer-2 -> server-b:50051
CUSTOMER_SERVICE / customer-3 -> server-a:50052
```

The registry supports:

```text
Register
Heartbeat
Lookup
Unregister
```

The C++ provider sends heartbeats at approximately TTL/3. If a process dies without unregistering, the registry eventually removes the instance when its TTL expires.

## 4. Create the Spring Boot proxy

The application should inject the proxy as `CustomerApi`.

For static discovery, Spring can provide a StaticServiceDiscovery bean from the configured logical service instances, then inject it into the non-generic GrpcClientManager. The service map describes service names and instance endpoints; it does not map protobuf types to hosts.

The cleaner production pattern is to create the manager with a factory and expose a JDK proxy:

```java
@Configuration
public class CustomerGrpcConfiguration {

    @Bean(destroyMethod = "close")
    GrpcClientManager customerClientManager(
            ServiceDiscovery serviceDiscovery) {

        return new GrpcClientManager(serviceDiscovery, 100);
    }

    @Bean
    CustomerApi customerApi(
            GrpcClientManager manager) {

        InvocationHandler handler = (proxy, method, args) ->
                manager.execute(
                        "CUSTOMER_SERVICE",
                        CustomerGrpcClient::new,
                        client -> method.invoke(client, args)
                );

        return (CustomerApi) Proxy.newProxyInstance(
                CustomerApi.class.getClassLoader(),
                new Class<?>[]{CustomerApi.class},
                handler
        );
    }
}
```

If the proxy is constructed centrally, application code is unchanged:

```java
@Service
public class CustomerService {

    private final CustomerApi customerApi;

    public CustomerService(CustomerApi customerApi) {
        this.customerApi = customerApi;
    }

    public String findCustomer(String id) {
        return customerApi.getCustomer(id);
    }
}
```

A controller can use the same interface:

```java
@RestController
@RequestMapping("/customers")
public class CustomerController {

    private final CustomerApi customerApi;

    public CustomerController(CustomerApi customerApi) {
        this.customerApi = customerApi;
    }

    @GetMapping("/{id}")
    public String getCustomer(@PathVariable String id) {
        return customerApi.getCustomer(id);
    }
}
```

Multiple application threads can safely invoke the same injected `CustomerApi`. The generated blocking stub and underlying channel are intended to be reused concurrently.

## 5. Failover behavior

Suppose discovery returns:

```text
customer-1 -> server-a:50051
customer-2 -> server-b:50051
customer-3 -> server-a:50052
```

The first call resolves and caches `customer-1`.

Subsequent calls reuse the existing client:

```text
call 1 -> customer-1
call 2 -> customer-1
call 3 -> customer-1
```

If a configured retryable gRPC failure occurs, the manager removes the failed active client and performs a new lookup excluding that instance:

```text
call 4
  |
  +-- customer-1 -> UNAVAILABLE
  |
  +-- Lookup(CUSTOMER_SERVICE,
  |          excluded=[customer-1])
  |
  +-- customer-2
  |
  +-- retry call -> success
```

If `customer-2` subsequently fails:

```text
Lookup(CUSTOMER_SERVICE,
       excluded=[customer-1, customer-2])

        -> customer-3
```

The exact retryable gRPC status codes are defined by the client manager. The current reference implementation retries:

```text
UNAVAILABLE
DEADLINE_EXCEEDED
RESOURCE_EXHAUSTED
```

Application code does not need to catch these failures and perform discovery itself.

## 6. Round-robin behavior

Discovery round-robin is performed when discovery is actually requested, not for every application call.

For three instances:

```text
Lookup -> customer-1
Lookup -> customer-2
Lookup -> customer-3
Lookup -> customer-1
...
```

Because normal calls use the active cached client, the system does not perform registry/static discovery on every RPC.

## 9. Adding another service

The same infrastructure can be reused for another API:

```java
public interface OrderApi {
    Order getOrder(String orderId);
    List<Order> getOrdersForCustomer(String customerId);
}
```

Create an adapter around its generated stub:

```java
public final class OrderGrpcClient implements OrderApi {

    private final OrderServiceGrpc.OrderServiceBlockingStub stub;

    public OrderGrpcClient(ManagedChannel channel) {
        this.stub = OrderServiceGrpc.newBlockingStub(channel);
    }

    // implement OrderApi using stub
}
```

Then reuse the same non-generic manager. The service-specific type lives at the factory/call boundary:

```java
GrpcClientManager clientManager =
        new GrpcClientManager(serviceDiscovery, 100);

clientManager.execute(
        "ORDER_SERVICE",
        OrderGrpcClient::new,
        client -> client.getOrder("12345")
);
```

The discovery configuration only needs the logical service:

```yaml
grpc:
  discovery:
    services:
      ORDER_SERVICE:
        - instance-id: order-1
          host: server-a
          port: 50101
        - instance-id: order-2
          host: server-b
          port: 50101
```

There is no application configuration that says "OrderServiceGrpc -> server-b".

## 10. Reflection and generic application code

Because the application-facing API is an interface, the proxy can also be invoked through reflection:

```java
Method method =
        CustomerApi.class.getMethod("getCustomer", String.class);

Object result =
        method.invoke(customerApi, "12345");
```

The JDK proxy intercepts the invocation exactly like a normal Java call.

This is useful when existing CORBA-style code dynamically invokes service operations and the migration needs to preserve that calling pattern.

## 11. Channel and stub lifecycle

### Type association

The manager is intentionally **not generic**. There is no `GrpcClientManager<CustomerApi>` or `GrpcClientManager`.

The only generic type is the factory:

```java
@FunctionalInterface
public interface GrpcClientFactory<T> {
    T create(ManagedChannel channel);
}
```

For example:

```java
CustomerGrpcClient::new
```

is a `GrpcClientFactory<CustomerGrpcClient>`, while:

```java
OrderGrpcClient::new
```

is a `GrpcClientFactory<OrderGrpcClient>`.

The manager therefore stays transport/discovery infrastructure. At the point where a service operation is executed, the factory tells the manager how to turn the selected channel into the correct service-specific adapter.

### Are channels separated by Java type?

**No. Channels are not keyed or separated by Java API type.**

The current implementation caches an active `GrpcClient` by **logical service name**:

```text
CUSTOMER_SERVICE -> one active discovered instance -> one ManagedChannel
ORDER_SERVICE    -> one active discovered instance -> one ManagedChannel
```

The service-specific adapter/stub is created on that channel:

```text
CUSTOMER_SERVICE
    |
    +-- ServiceTarget(customer-1, server-a:50051)
    |
    +-- ManagedChannel
    |
    +-- CustomerGrpcClient
          |
          +-- CustomerServiceGrpc.CustomerServiceBlockingStub
```

If two logical services happen to resolve to the same host and port, the current implementation still treats them as separate service connections and creates separate channels. **It does not currently share channels across service types.**

That is deliberate: channel sharing is a separate optimization/connection-management concern and should not be inferred merely because two service endpoints happen to match.

If channel sharing is needed later, introduce an explicit connection/channel cache keyed by a connection identity such as the normalized endpoint (and, in a secured deployment, any TLS/security identity that affects connection reuse). Multiple service-specific adapters can then be built on the same channel.

### Client lifecycle

A `GrpcClient` represents the resources for one discovered service target:

```text
ServiceTarget
    +
ManagedChannel
    +
service-specific adapter/stub
```

Channels and generated stubs should not be created for every RPC.

The manager owns the cached clients and retires them when they are evicted, replaced, or the manager is closed.

Do not shut down a cached channel merely because one RPC failed. A channel can be used concurrently by other application threads; lifecycle belongs to the client manager/cache.

A discovered channel is tied to the service-specific adapter created with it: CustomerGrpcClient is constructed with that ManagedChannel, and its generated CustomerServiceBlockingStub uses the same channel. On a retryable exception, the manager removes and invalidates the failed GrpcClient, shuts down its old channel through the cache removal listener, performs a new discovery lookup excluding the failed instance, creates a new ManagedChannel, and calls CustomerGrpcClient::new with that new channel. The CustomerApi proxy remains the same; the client and channel underneath it are replaced.

## 12. Channel creation and failover walkthrough

The channel is created for a discovered service target, and the service-specific client is constructed on that channel. `CustomerGrpcClient::new` is effectively `channel -> new CustomerGrpcClient(channel)`.

Initial call:

```
CustomerApi.getCustomer("123")
        |
        v
JDK proxy
        |
        v
GrpcClientManager.execute(...)
        |
        v
ServiceDiscovery.lookup(...)
        |
        v
customer-1 -> server-a:50051
        |
        v
ManagedChannel #1
        |
        v
CustomerGrpcClient #1
        |
        v
CustomerServiceBlockingStub
        |
        v
RPC
```

The manager caches that client as the active client for `CUSTOMER_SERVICE`. Subsequent calls reuse the client and channel. gRPC recommends reusing channels and stubs when possible. citeturn0search1

When a retryable RPC exception such as `UNAVAILABLE` occurs:

1. The manager catches the `StatusRuntimeException`.
2. It removes the active `GrpcClient`.
3. It invalidates the cache entry; the removal listener retires the old client and shuts down its channel.
4. It performs fresh discovery while excluding the failed instance.
5. Suppose discovery returns `customer-2 -> server-b:50051`.
6. The manager creates `ManagedChannel #2`.
7. `CustomerGrpcClient::new` receives channel #2 and creates a new generated stub on it.
8. The original operation is retried using the new client.

```
OLD
CustomerGrpcClient #1
        |
ManagedChannel #1
        |
customer-1
        |
   UNAVAILABLE
        |
        v
invalidate + retire
        |
        v
fresh lookup excluding customer-1
        |
        v
NEW
ManagedChannel #2
        |
CustomerGrpcClient #2
        |
customer-2
        |
retry original RPC
```

The application-facing `CustomerApi` proxy does not change. Only the client/channel resources underneath it are replaced.

## 13. Multiple channels for the same logical service

The current implementation intentionally maintains one active `GrpcClient` and one `ManagedChannel` per logical service name. It does not create one channel per Java type.

A future channel pool could look like:

```
CUSTOMER_SERVICE
       |
   channel pool
   /    |    \
 #1    #2    #3
 |     |     |
pod A pod B pod C
```

Multiple channels can help when high concurrency or long-lived RPCs make a single HTTP/2 connection a bottleneck. gRPC documents separate channels or channel pools as options for these high-load cases. citeturn0search1

The trade-offs are more connections, more keepalive/connection overhead, more memory, and more complex lifecycle, load distribution, failover, and observability.

For this project, one active channel per logical service is the initial choice. If pooling becomes necessary, introduce an explicit transport-level channel pool rather than making `GrpcClientManager<T>` generic.

## 14. Kubernetes migration

A `GrpcClient` represents the connection resources for one discovered target:

```text
ServiceTarget
    +
ManagedChannel
    +
service-specific adapter/stub
```

Channels and generated stubs should not be created for every RPC.

The manager owns the cached clients and retires them when they are replaced or the manager is closed.

Do not shut down a shared cached channel merely because one RPC failed. A channel can be used concurrently by other application threads; lifecycle belongs to the client manager/cache.

## 12. Kubernetes migration

The application-facing API does not need to change when moving from the homegrown registry to Kubernetes.

The discovery abstraction is:

```java
public interface ServiceDiscovery {

    ServiceTarget lookup(
            String serviceName,
            Set<String> excludedInstanceIds);
}
```

Today:

```text
ServiceDiscovery
      |
      +-- RegistryServiceDiscovery
      |
      +-- StaticServiceDiscovery
```

Later:

```text
ServiceDiscovery
      |
      +-- KubernetesServiceDiscovery
```

The proxy, client manager and application APIs can remain unchanged.

A Kubernetes implementation can resolve a Service DNS name and return a `ServiceTarget`. If exact pod-level exclusion is required, the Kubernetes discovery implementation needs endpoint-level information rather than only a normal Service DNS name.

## 15. Production considerations

The checked-in reference implementation uses plaintext gRPC for local development.

For production deployments, add the application's standard:

- TLS/mTLS configuration
- authentication and authorization
- RPC deadlines
- channel keepalive settings appropriate for the environment
- structured logging
- metrics/tracing
- bounded retry policies
- graceful shutdown
- registry authentication and access control

Do not use cache expiration as a substitute for service health detection. Cache lifecycle and service availability are separate concerns.

## 16. Core design principle

The application should see:

```text
CustomerApi
```

and should not need to know whether the service is currently located through:

```text
static configuration
       |
       v
homegrown registry
       |
       v
Kubernetes
```

The discovery implementation can change while the application-facing API, failover proxy and client lifecycle remain stable.

# Section 2 — C++ service provider

The C++17 side provides a small registration helper for backend services.

## 2.1 Registration lifecycle

A C++ service starts its normal gRPC server, creates a registry channel, registers its logical service name and instance ID, sends heartbeats, and unregisters during normal shutdown.

```cpp
auto registry_channel =
    grpc::CreateChannel(
        "127.0.0.1:9090",
        grpc::InsecureChannelCredentials());

grpcdiscovery::ServiceProvider provider(
    registry_channel,
    "CUSTOMER_SERVICE",
    "customer-1",
    "127.0.0.1",
    50051,
    30);

provider.start();
```

Lifecycle:

```
start
  |
  +-- Register
  |
  +-- Heartbeat approximately every TTL/3
  |
  +-- normal shutdown -> Unregister
```

## 2.2 Multiple C++ instances

```
CUSTOMER_SERVICE
    |
    +-- customer-1 -> 127.0.0.1:50051
    +-- customer-2 -> 127.0.0.1:50052
    +-- customer-3 -> 127.0.0.1:50053
```

Same-host/different-port instances are supported.

## 2.3 Build

```bash
cmake -S cpp -B cpp/build
cmake --build cpp/build -j
```

The C++ provider uses the shared `src/main/proto/registry.proto`.

## 2.4 Production considerations

The checked-in example uses insecure/plaintext gRPC for local development. Production deployments should add TLS/mTLS, authentication and authorization, RPC deadlines, appropriate keepalive settings, logging, metrics/tracing, bounded retry policies, graceful shutdown, and registry access control.
