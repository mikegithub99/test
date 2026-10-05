# Java 8 / Spring example

## Kubernetes configuration

```yaml
grpc:
  customer:
    host: customer-service
    port: 50051
  client:
    max-attempts: 3
    retry-delay: 0ms
```

The application connects to the stable Kubernetes Service name. It does not discover or cache individual Pod addresses.

## Interim non-Kubernetes configuration

```yaml
grpc:
  customer:
    host: server-a
    port: 50051
  client:
    max-attempts: 3
    retry-delay: 0ms
```

## Spring configuration

```java
@Bean
ServiceEndpointProvider endpointProvider(GrpcProperties p) {
    return new ServiceEndpointProvider() {
        @Override
        public ServiceEndpoint endpoint(String name) {
            return ServiceEndpoint.hostPort(
                name,
                p.getCustomer().getHost(),
                p.getCustomer().getPort());
        }
    };
}

@Bean(destroyMethod = "close")
GrpcClientManager grpcClientManager(
        ServiceEndpointProvider endpoints,
        GrpcProperties p) {
    return new GrpcClientManager(
        endpoints,
        p.getClient().getMaxAttempts(),
        p.getClient().getRetryDelay());
}

@Bean
CustomerApi customerApi(GrpcClientManager manager) {
    return new CustomerApiAdapter(manager);
}
```

## Adapter and generated stub

The generated stub is bound directly in `CustomerApiAdapter`, not in a separate `CustomerGrpcClient` wrapper.

```java
this.factory = new GrpcClientFactory<Object>() {
    @Override
    public Object create(ManagedChannel channel) {
        return CustomerServiceGrpc.newBlockingStub(channel);
    }
};
```

Then the adapter uses the cached stub:

```java
return manager.execute(
    "CUSTOMER_SERVICE",
    factory,
    new Function<Object, String>() {
        @Override
        public String apply(Object stub) {
            GetCustomerRequest request = GetCustomerRequest.newBuilder()
                    .setCustomerId(customerId)
                    .build();

            return ((CustomerServiceGrpc.CustomerServiceBlockingStub) stub)
                    .getCustomer(request)
                    .getName();
        }
    });
```

Replace the example generated type and request/response names with the classes generated from your `.proto`.

## Runtime flow

Application -> CustomerApi -> CustomerApiAdapter -> GrpcClientManager -> generated stub + channel -> Kubernetes Service -> ready Pods.