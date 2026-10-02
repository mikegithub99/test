# Spring Kubernetes Configuration Example

This example shows the intended Spring setup for running the Java 8 gRPC client inside Kubernetes.

The application-facing APIs and GrpcClientManager remain the same. Only the ServiceDiscovery implementation changes to KubernetesServiceDiscovery.

## 1. application.yml

~~~yaml
grpc:
  discovery:
    mode: KUBERNETES
    kubernetes:
      namespace: ${KUBERNETES_NAMESPACE:default}
      cluster-domain: ${KUBERNETES_CLUSTER_DOMAIN:cluster.local}
      services:
        CUSTOMER_SERVICE:
          host: ${CUSTOMER_SERVICE_HOST:customer-service}
          port: ${CUSTOMER_SERVICE_PORT:50051}
        ORDER_SERVICE:
          host: ${ORDER_SERVICE_HOST:order-service}
          port: ${ORDER_SERVICE_PORT:50052}
    client:
      max-attempts: ${GRPC_CLIENT_MAX_ATTEMPTS:3}
~~~

## 2. Spring configuration

~~~java
package com.example.config;

import com.example.discovery.GrpcClientManager;
import com.example.discovery.KubernetesServiceDiscovery;
import com.example.discovery.ServiceDiscovery;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.Map;

@Configuration
public class KubernetesGrpcConfiguration {

    @Bean
    public ServiceDiscovery serviceDiscovery(
            @Value("${grpc.discovery.kubernetes.namespace}") String namespace,
            @Value("${grpc.discovery.kubernetes.cluster-domain}") String clusterDomain,
            @Value("${grpc.discovery.kubernetes.services.CUSTOMER_SERVICE.host}") String customerHost,
            @Value("${grpc.discovery.kubernetes.services.CUSTOMER_SERVICE.port}") int customerPort,
            @Value("${grpc.discovery.kubernetes.services.ORDER_SERVICE.host}") String orderHost,
            @Value("${grpc.discovery.kubernetes.services.ORDER_SERVICE.port}") int orderPort) {

        Map<String, KubernetesServiceDiscovery.KubernetesService> services =
                new LinkedHashMap<String, KubernetesServiceDiscovery.KubernetesService>();

        services.put(
                "CUSTOMER_SERVICE",
                new KubernetesServiceDiscovery.KubernetesService(
                        customerHost, customerPort));

        services.put(
                "ORDER_SERVICE",
                new KubernetesServiceDiscovery.KubernetesService(
                        orderHost, orderPort));

        return new KubernetesServiceDiscovery(
                namespace, clusterDomain, services);
    }

    @Bean
    public GrpcClientManager grpcClientManager(
            ServiceDiscovery serviceDiscovery,
            @Value("${grpc.discovery.client.max-attempts:3}") int maxAttempts) {

        return new GrpcClientManager(serviceDiscovery, maxAttempts);
    }
}
~~~

## 3. Customer API proxy

The Kubernetes migration does not change the application-facing API.

~~~java
@Bean
public CustomerApi customerApi(GrpcClientManager manager) {
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

The service-specific CustomerGrpcClient still owns the generated gRPC stub type.

## 4. Kubernetes Services

~~~yaml
apiVersion: v1
kind: Service
metadata:
  name: customer-service
spec:
  selector:
    app: customer-service
  ports:
    - name: grpc
      port: 50051
      targetPort: 50051
---
apiVersion: v1
kind: Service
metadata:
  name: order-service
spec:
  selector:
    app: order-service
  ports:
    - name: grpc
      port: 50052
      targetPort: 50052
~~~

Inside the cluster, the resulting targets are:

customer-service.default.svc.cluster.local:50051
order-service.default.svc.cluster.local:50052

## 5. Namespace handling

If the client and server Services are in the same namespace, a short host such as customer-service is sufficient for Kubernetes DNS.

The discovery implementation expands it to:

customer-service.<namespace>.svc.<cluster-domain>

For cross-namespace access, configure the appropriate namespace explicitly.

## 6. Kubernetes failover behavior

The manager still retries configured gRPC failures.

With the external registry:

CUSTOMER_SERVICE
  -> customer-1
  -> customer-2
  -> customer-3

With normal Kubernetes Service discovery:

CUSTOMER_SERVICE
  -> customer-service.default.svc.cluster.local:50051
  -> Kubernetes Service routing
  -> customer pods

The manager cannot use excludedInstanceIds to guarantee exclusion of a particular pod because the Service endpoint does not expose pod identity.

If exact pod-level failover is required later, add EndpointSlice-aware discovery rather than changing the application API.

## 7. Environment variables

A Kubernetes Deployment can supply values from environment variables:

~~~yaml
env:
  - name: KUBERNETES_NAMESPACE
    valueFrom:
      fieldRef:
        fieldPath: metadata.namespace

  - name: CUSTOMER_SERVICE_HOST
    value: customer-service

  - name: CUSTOMER_SERVICE_PORT
    value: "50051"

  - name: ORDER_SERVICE_HOST
    value: order-service

  - name: ORDER_SERVICE_PORT
    value: "50052"
~~~

Using the Downward API for the namespace avoids hard-coding the deployment namespace.

## 8. Production transport security

The example follows the repository baseline and leaves channel transport security outside discovery.

The current manager uses plaintext gRPC channels. For production TLS or mTLS, make channel construction configurable in the client manager rather than adding credentials to KubernetesServiceDiscovery.

Kubernetes discovery remains responsible only for:

logical service -> network target
