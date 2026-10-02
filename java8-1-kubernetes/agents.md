# AGENTS.md

## Purpose

This folder is the Java 8 Kubernetes discovery variant of the existing gRPC client architecture.

Use java8-1 as the behavioral baseline. The goal is to replace external registry discovery with Kubernetes Service discovery without redesigning the application-facing API or client lifecycle.

## Reproduction and compatibility rules

Preserve the existing public class names, constructors, methods, and behavioral contracts where practical.

Do not rename or redesign existing discovery or client abstractions unless explicitly requested.

The java8-1 semantics remain the baseline:

- one authoritative active client per logical service;
- one active ManagedChannel per logical service;
- retired clients drain existing operations before channel shutdown;
- per-operation leases;
- identity-checked replacement;
- bounded retry attempts;
- cumulative failed-instance exclusions;
- service-specific generated stubs outside generic infrastructure;
- discovery behind ServiceDiscovery.

Kubernetes changes discovery behavior, not these lifecycle contracts.

## Discovery abstraction

KubernetesServiceDiscovery must implement:

ServiceTarget lookup(String serviceName, Set<String> excludedInstanceIds)

Do not make GrpcClientManager depend directly on Kubernetes classes.

## Kubernetes Service model

The first implementation uses Kubernetes Service DNS as the stable endpoint.

Example:

CUSTOMER_SERVICE
  -> customer-service.default.svc.cluster.local:50051

The Kubernetes Service is the logical load-balancing endpoint.

Do not claim that normal Kubernetes Service DNS guarantees selection of a different pod after a failure.

## Excluded instance IDs

Normal Kubernetes Service discovery does not expose pod identity through the Service DNS endpoint. Do not invent fake pod-level failover semantics.

If exact pod-level exclusion is required, introduce an EndpointSlice-aware implementation behind ServiceDiscovery.

## Generic infrastructure

Do not add Kubernetes-specific logic to GrpcClientManager, GrpcClient, GrpcClientFactory, or service-specific generated adapters.

## Channel lifecycle

Preserve:

ACTIVE -> RETIRING -> CLOSED

Never immediately shut down a channel that may have in-flight operations.

Do not replace the single authoritative active-client cache with another current-client map.

Do not automatically share channels between logical services.

## Java 8 compatibility

All code in this folder must compile on Java 8.

Do not use records, List.of, Set.of, Map.of, Map.copyOf, Stream.toList, var, pattern matching, or other Java 9+ language/API features.

## Configuration

Kubernetes configuration maps logical service names to Kubernetes Service endpoints.

Example:

~~~yaml
grpc:
  discovery:
    mode: KUBERNETES
    kubernetes:
      namespace: default
      cluster-domain: cluster.local
      services:
        CUSTOMER_SERVICE:
          host: customer-service
          port: 50051
~~~

Do not configure generated protobuf or gRPC stub classes as discovery data.

## Transport security

Do not put TLS, mTLS, credentials, interceptors, or channel options into Kubernetes discovery.

The current baseline uses plaintext channels. Production transport security should be configurable in the channel creation layer.

## Validation

Before changing this implementation, verify:

1. Java 8 compatibility.
2. KubernetesServiceDiscovery implements ServiceDiscovery.
3. Logical service names resolve to stable Kubernetes Service DNS targets.
4. Short service names correctly include namespace and cluster domain.
5. Qualified DNS names are not double-expanded.
6. Invalid namespace, service, host, or port configuration fails clearly.
7. No Kubernetes-specific dependency leaks into generic manager code.
8. No claim is made that Service DNS guarantees a different pod.
9. Existing manager lifecycle and failover semantics remain unchanged.
10. Exact pod-level exclusion remains an explicit future endpoint-aware concern.

## Change discipline

When modifying this folder:

- read agents.md;
- read design.md;
- inspect current source before changing it;
- preserve existing semantics unless the request explicitly changes them;
- make the smallest coherent change;
- update design.md or example.md when configuration or architecture changes;
- do not silently redesign the manager.
