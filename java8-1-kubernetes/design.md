# Java 8 Kubernetes Discovery Design

This folder is the Kubernetes deployment variant of the Java 8 gRPC discovery/client design.

The application-facing API, GrpcClientManager, GrpcClient, GrpcClientFactory, and service-specific adapters remain unchanged. Only discovery changes.

## Architecture

Spring singleton API
  -> GrpcClientManager
  -> KubernetesServiceDiscovery
  -> Kubernetes Service DNS
  -> Kubernetes Service
  -> backend pods

Kubernetes-specific behavior stays behind ServiceDiscovery.

## Kubernetes discovery model

KubernetesServiceDiscovery maps a logical service name to a Kubernetes Service DNS endpoint.

Example:

CUSTOMER_SERVICE
  -> customer-service.default.svc.cluster.local:50051

The Kubernetes Service is the stable logical endpoint and Kubernetes networking/load balancing selects backend pods.

The implementation accepts either a short Service name or a qualified DNS host.

## Failover difference

The external registry can return individual instance IDs and support explicit exclusion:

attempt 1 -> instance A
attempt 2 -> exclude A -> instance B
attempt 3 -> exclude A,B -> instance C

A normal Kubernetes Service DNS name does not expose pod identity to this discovery class.

Therefore excludedInstanceIds is accepted by the common interface but is not used for pod selection here.

A subsequent RPC may be routed to a different healthy pod by Kubernetes, but this implementation does not guarantee a different pod.

If exact pod-level exclusion is required later, add an EndpointSlice-aware implementation behind ServiceDiscovery.

## Channel ownership

Keep the existing rule:

CustomerApi -> CustomerGrpcClient -> ManagedChannel A
OrderApi -> OrderGrpcClient -> ManagedChannel B

Do not automatically share channels between logical services.

## DNS target

For a short Service name, the implementation constructs:

service.namespace.svc.cluster-domain:port

The default cluster domain is cluster.local.

The Service DNS name remains stable while pod membership changes behind the Service.

## Configuration boundary

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

Generated protobuf class names do not belong in discovery configuration.

## TLS

The discovery class does not configure TLS.

The current Java 8 baseline uses plaintext channels. Production TLS or mTLS should be configurable in the channel creation layer, not in ServiceDiscovery.

## Migration

The same application-facing API can move between STATIC, REGISTRY, and KUBERNETES because all three implement ServiceDiscovery.

## Future endpoint-aware implementation

If exact instance IDs, pod health, or failed-pod exclusion become requirements, introduce an EndpointSlice-based discovery implementation.

It can return a ServiceTarget containing a pod identity and pod address. The existing GrpcClientManager can then use its current exclusion and failover logic without knowing that the source is Kubernetes.

## Non-goals

This first implementation does not:

- call the Kubernetes API;
- watch EndpointSlices;
- discover pods directly;
- implement its own Kubernetes load balancer;
- change GrpcClientManager;
- change application-facing APIs;
- automatically share channels;
- provide pod-level exclusion.
