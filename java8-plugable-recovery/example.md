# Spring Example

Kubernetes:
    @Bean
    GrpcClientManager grpcClientManager(ServiceDiscovery discovery, int maxAttempts) {
        return new GrpcClientManager(discovery, new KubernetesRecoveryPolicy(), 100, maxAttempts);
    }

External registry:
    @Bean
    GrpcClientManager grpcClientManager(ServiceDiscovery discovery, int maxAttempts) {
        return new GrpcClientManager(discovery, new RegistryRecoveryPolicy(), 100, maxAttempts);
    }

Application proxy:
    @Bean
    CustomerApi customerApi(GrpcClientManager manager) {
        InvocationHandler handler = (proxy, method, args) ->
            manager.execute("CUSTOMER_SERVICE", CustomerGrpcClient::new, client -> {
                try {
                    return method.invoke(client, args);
                } catch (InvocationTargetException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof RuntimeException) throw (RuntimeException) cause;
                    if (cause instanceof Error) throw (Error) cause;
                    throw e;
                }
            });

        return (CustomerApi) Proxy.newProxyInstance(
            CustomerApi.class.getClassLoader(),
            new Class<?>[]{CustomerApi.class}, handler);
    }

The exception unwrapping is intentional: Java reflection otherwise hides the
StatusRuntimeException inside InvocationTargetException.
