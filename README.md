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
   +----+-----+         generated gRPC stub
        |
        v
  service instance
```

The important separation is:

- `ServiceDiscovery` answers **where the service is**.
- `GrpcClientManager` owns cached channels/clients and lifecycle.
- The JDK proxy owns **transparent retry/failover behavior**.
- A service-specific adapter such as `CustomerGrpcClient` owns the generated gRPC stub and maps it to the application API.
- Application code depends only on `CustomerApi`.

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

A typical Spring configuration is:

```java
@Configuration
public class CustomerGrpcConfiguration {

    @Bean
    CustomerGrpcClient customerGrpcClient() {
        return new CustomerGrpcClient(null); // replaced below by the manager factory
    }
}
```

The cleaner production pattern is to create the manager with a factory and expose a JDK proxy:

```java
@Configuration
public class CustomerGrpcConfiguration {

    @Bean(destroyMethod = "close")
    GrpcClientManager<CustomerApi> customerClientManager(
            ServiceDiscovery serviceDiscovery) {

        return new GrpcClientManager<>(
                serviceDiscovery,
                CustomerGrpcClient::new,
                100
        );
    }

    @Bean
    CustomerApi customerApi(
            GrpcClientManager<CustomerApi> manager) {

        InvocationHandler handler = (proxy, method, args) ->
                manager.execute(
                        "CUSTOMER_SERVICE",
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

## 7. C++ service registration

A C++17 service creates a `ServiceProvider` after starting its normal gRPC server:

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

The process then owns the registration lifecycle:

```text
start
  |
  +-- Register
  |
  +-- Heartbeat every ~TTL/3
  |
  +-- normal shutdown -> Unregister
```

Build the C++ provider with:

```bash
cmake -S cpp -B cpp/build
cmake --build cpp/build -j
```

The C++ provider uses the shared `src/main/proto/registry.proto`.

## 8. Multiple C++ instances

Run multiple processes with unique instance IDs:

```text
CUSTOMER_SERVICE
  customer-1 -> 127.0.0.1:50051
  customer-2 -> 127.0.0.1:50052
  customer-3 -> 127.0.0.1:50053
```

Same-host/different-port instances are supported.

Instance IDs should remain unique for a logical service.

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

Then create a manager using:

```java
GrpcClientManager<OrderApi> orderManager =
        new GrpcClientManager<>(
                serviceDiscovery,
                OrderGrpcClient::new,
                100
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

## 13. Production considerations

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

## 14. Core design principle

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
