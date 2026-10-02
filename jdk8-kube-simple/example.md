# Spring examples

## Kubernetes application.yml

    grpc:
      customer:
        host: customer-service
        port: 50051
      client:
        max-attempts: 3
        retry-delay: 0ms

## Interim non-Kubernetes application.yml

    grpc:
      customer:
        host: server-a
        port: 50051
      client:
        max-attempts: 3
        retry-delay: 0ms

## Spring wiring

    @Bean
    ServiceEndpointProvider endpointProvider(GrpcProperties p) {
        return name -> ServiceEndpoint.hostPort(name, p.getCustomer().getHost(), p.getCustomer().getPort());
    }

    @Bean(destroyMethod = "close")
    GrpcClientManager grpcClientManager(GrpcProperties p, ServiceEndpointProvider endpoints) {
        return new GrpcClientManager(endpoints, p.getClient().getMaxAttempts(), p.getClient().getRetryDelay());
    }

    @Bean
    CustomerApi customerApi(GrpcClientManager manager) {
        return GrpcApiProxyFactory.create(
            CustomerApi.class,
            "CUSTOMER_SERVICE",
            manager,
            CustomerGrpcClient::new);
    }

For Java 8, use ordinary Spring configuration/property classes rather than Java records. The library itself is Spring-independent.
