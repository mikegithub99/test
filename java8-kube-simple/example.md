# Spring examples

## Kubernetes application.yml

    grpc:
      customer:
        host: customer-service
        port: 50051
      client:
        max-attempts: 3
        retry-delay: 0ms

The host is the Kubernetes Service name. A fully qualified name such as customer-service.default.svc.cluster.local can also be used.

## Interim non-Kubernetes application.yml

    grpc:
      customer:
        host: server-a
        port: 50051
      client:
        max-attempts: 3
        retry-delay: 0ms

Only one host/port is configured.

## Spring wiring

    @Bean
    ServiceEndpointProvider endpointProvider(GrpcProperties p) {
        return name -> ServiceEndpoint.hostPort(name, p.customer().host(), p.customer().port());
    }

    @Bean(destroyMethod = "close")
    GrpcClientManager grpcClientManager(ServiceEndpointProvider endpoints, GrpcProperties p) {
        return new GrpcClientManager(endpoints, p.client().maxAttempts(), p.client().retryDelay());
    }

    @Bean
    CustomerApi customerApi(GrpcClientManager manager) {
        return GrpcApiProxyFactory.create(CustomerApi.class, "CUSTOMER_SERVICE", manager, CustomerGrpcClient::new);
    }

    @ConfigurationProperties(prefix = "grpc")
    public record GrpcProperties(Customer customer, Client client) {
        public record Customer(String host, int port) {}
        public record Client(int maxAttempts, Duration retryDelay) {}
    }

Use @EnableConfigurationProperties(GrpcProperties.class). The library itself is Spring-independent.