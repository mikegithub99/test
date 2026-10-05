# Java 8 / Spring example

## Configuration

Kubernetes:

    grpc:
      customer:
        host: customer-service
        port: 50051
      client:
        max-attempts: 3
        retry-delay: 0ms

Interim non-Kubernetes:

    grpc:
      customer:
        host: server-a
        port: 50051
      client:
        max-attempts: 3
        retry-delay: 0ms

## Spring wiring

Use ordinary Java 8 property/configuration classes.

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

The application calls the normal business interface:

    customerApi.getCustomer("123");

The adapter invokes the generated gRPC client through GrpcClientManager. No dynamic proxy is involved.

## Generated gRPC binding

CustomerGrpcClient is intentionally the only application-specific gRPC binding point. Replace its placeholder code with your generated CustomerServiceGrpc stub and request/response types.
