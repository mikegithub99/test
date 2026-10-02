# Simple Kubernetes-oriented gRPC client

## Purpose

This module is the simplified replacement for the earlier registry/plugable-recovery designs. Production assumes classic Kubernetes Service discovery. The interim environment needs only one Spring-configured host and port.

## Architecture

    Application API
          |
      dynamic proxy
          |
    GrpcClientManager
          |
    one long-lived client/channel
          |
    configured target
          |
      Kubernetes Service
          |
       ready Pods

In Kubernetes the target is normally customer-service:50051. Kubernetes owns Service endpoint selection. The application does not query Kubernetes, watch EndpointSlices, maintain Pod IDs, or implement endpoint load balancing.

## Why keep the proxy?

The proxy gives the application a stable business API and makes bounded retry transparent. A retryable StatusRuntimeException causes the manager to retry the same operation using the existing client/channel. This is application-level retry, not Pod discovery.

Default retryable statuses are UNAVAILABLE, DEADLINE_EXCEEDED, and RESOURCE_EXHAUSTED. Non-retryable errors are propagated. Retries are bounded. Mutating operations must only be retried when their semantics tolerate duplicate execution or use an idempotency mechanism.

## Why no EndpointSlice?

EndpointSlice would make the application responsible for endpoint identity, watches, stale endpoint handling, Pod lifecycle, Kubernetes API permissions, and client-side selection. None of those are required when the desired target is a normal Kubernetes Service. Keeping that responsibility in Kubernetes makes the Java client smaller and less coupled to the cluster API.

## Why no recovery-policy abstraction?

There is one normal recovery rule for this design: retry a bounded set of retryable gRPC failures on the existing client; otherwise fail. The registry-specific REPLACE_CLIENT behavior and instance exclusion are intentionally removed.

## Interim non-Kubernetes mode

Spring supplies exactly one endpoint, for example server-a:50051. There is no registry and no list of instances. The same proxy, manager, adapter, and application API are used.

## Spring configuration

Kubernetes:

    grpc:
      customer:
        host: customer-service
        port: 50051
      client:
        max-attempts: 3
        retry-delay: 0ms

Non-Kubernetes:

    grpc:
      customer:
        host: server-a
        port: 50051
      client:
        max-attempts: 3
        retry-delay: 0ms

Spring wiring is shown in example.md.

## Application API and adapter

    public interface CustomerApi {
        String getCustomer(String customerId);
    }

    public final class CustomerGrpcClient {
        private final CustomerServiceGrpc.CustomerServiceBlockingStub stub;
        public CustomerGrpcClient(ManagedChannel channel) { this.stub = CustomerServiceGrpc.newBlockingStub(channel); }
        public String getCustomer(String customerId) {
            return stub.getCustomer(GetCustomerRequest.newBuilder().setCustomerId(customerId).build()).getName();
        }
    }

Then expose one singleton CustomerApi proxy through Spring.

## Lifecycle and security

The manager lazily creates one client/channel per logical service and reuses it concurrently. Spring closes the manager during application shutdown. The sample uses plaintext only to keep the example focused; production TLS belongs in channel construction.

## Non-goals

No external registry, EndpointSlice discovery, Pod-level failover, instance exclusion, client-side load balancing, recovery-policy hierarchy, or channel replacement after RPC failure.
