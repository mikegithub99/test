# Option B — Kubernetes EndpointSlice + gRPC round_robin

## 1. Purpose

Option B keeps the existing application-facing adapter and retry proxy, but moves backend discovery and endpoint load balancing into Kubernetes plus gRPC.

Architecture:

    Application
        |
    CustomerAdapterGrpc
        |
    GrpcRetryProxy
        |
    CustomerAdapterGrpcImpl
        |
    ManagedChannel
        |
    kube:///customer-service
        |
    Kubernetes NameResolver
        |
    Kubernetes EndpointSlice API
        |
    gRPC round_robin
        |
    ready Pod subchannels
        |
    backend Pods

The application never receives Pod IPs and never chooses a Pod.

## 2. Why Option B

Option B avoids a custom client manager and avoids an Envoy/Consul control plane.

Our code is responsible for translating Kubernetes EndpointSlice state into gRPC resolver addresses. gRPC is responsible for long-lived subchannels and round_robin selection. Kubernetes remains responsible for Pod lifecycle and EndpointSlice maintenance.

This gives a small separation of responsibilities:

- Kubernetes: current Service membership and Pod lifecycle.
- EndpointSlice discovery code: translate Kubernetes state into addresses.
- gRPC NameResolver: publish addresses to the channel.
- gRPC round_robin: select among ready subchannels.
- GrpcRetryProxy: bounded retry policy.
- Adapter: application request/response translation.

## 3. Kubernetes Service

This implementation is intended for a headless Service because the client is deliberately discovering individual Pod endpoints.

Example:

    apiVersion: v1
    kind: Service
    metadata:
      name: customer-service
    spec:
      clusterIP: None
      selector:
        app: customer
      ports:
        - name: grpc
          port: 50051
          targetPort: 50051

The important setting is:

    clusterIP: None

The Service name remains stable while Pod IPs may change.

Kubernetes maintains EndpointSlices for the Service. The resolver watches EndpointSlices labeled with:

    kubernetes.io/service-name=customer-service

## 4. Target

The adapter uses:

    kube:///customer-service

This is a logical target, not a Pod address.

The kube NameResolver extracts the Service name, asks the Kubernetes API for EndpointSlices in the configured namespace, and publishes ready endpoint IP/port pairs to gRPC.

## 5. Classes

### AdapterGrpcBase

Creates one long-lived ManagedChannel and one generated blocking stub.

The channel uses:

    ManagedChannelBuilder.forTarget(target)
        .defaultLoadBalancingPolicy("round_robin")
        .usePlaintext()
        .build();

The stub is created once and reused concurrently.

### CustomerAdapterGrpcImpl

Builds the protobuf request and invokes the generated blocking stub. It does not know about Kubernetes or Pod selection.

### KubernetesNameResolverProvider

Registers the kube:// scheme with gRPC.

### KubernetesNameResolver

Receives the logical Service target and converts the current EndpointSlice result into gRPC EquivalentAddressGroup entries.

Whenever discovery changes, the resolver publishes a new address list.

### KubernetesEndpointSliceDiscovery

Uses the Kubernetes Java client directly against discovery.k8s.io/v1 EndpointSlice.

It:

1. Lists all EndpointSlices for the Service.
2. Stores the complete current slice set.
3. Aggregates endpoints across all slices.
4. Filters to ready/serving endpoints.
5. Uses the Service's grpc port when present.
6. Deduplicates address/port pairs.
7. Watches ADDED, MODIFIED, and DELETED events.
8. Rebuilds the complete endpoint set after changes.
9. Reconnects after watch/list failures with bounded backoff.
10. Re-lists after a failed watch so state is rebuilt from Kubernetes.

### GrpcKubernetesConfiguration

Creates the Kubernetes ApiClient and registers the kube NameResolverProvider.

### GrpcClientConfiguration

Creates the retry policy and exposes the retrying customerAdapter bean.

### GrpcRetryProxy / GrpcRetryPolicy

These are the existing retry components carried into Option B unchanged. The retry proxy owns retry count, status selection, and backoff. It does not own endpoint selection.

## 6. Detailed invocation sequence

Assume Kubernetes currently reports:

    Pod A = 10.0.1.10:50051
    Pod B = 10.0.1.11:50051
    Pod C = 10.0.1.12:50051

### Step 1 — application calls the adapter

The application invokes:

    customerAdapter.getCustomer("123")

The injected bean is the retry proxy implementing CustomerAdapterGrpc.

### Step 2 — retry proxy invokes the concrete adapter

The proxy invokes CustomerAdapterGrpcImpl.

The retry attempt counter is local to this one invocation, so the singleton proxy can safely serve concurrent application calls.

### Step 3 — adapter invokes the generated stub

CustomerAdapterGrpcImpl builds GetCustomerRequest and calls the generated blocking stub.

No channel or stub is created for the individual RPC.

### Step 4 — ManagedChannel uses the kube target

The channel was created for:

    kube:///customer-service

The registered kube NameResolverProvider creates KubernetesNameResolver.

### Step 5 — EndpointSlice discovery supplies addresses

The discovery component has listed and is watching all EndpointSlices for customer-service.

It aggregates:

    10.0.1.10:50051
    10.0.1.11:50051
    10.0.1.12:50051

and publishes them to the resolver.

### Step 6 — gRPC creates subchannels

gRPC receives the address list and creates/maintains subchannels for the backend addresses.

Conceptually:

    A -> subchannel A
    B -> subchannel B
    C -> subchannel C

These are long-lived connections managed by gRPC.

### Step 7 — round_robin selects a ready subchannel

With A, B, and C ready, round_robin distributes RPCs across the ready subchannels.

For a stable three-Pod set, the expected pattern is:

    A -> B -> C -> A -> B -> C -> ...

This should be understood as round-robin over the currently ready subchannels. If a subchannel is reconnecting, unavailable, or the endpoint set changes, the exact sequence changes.

### Step 8 — the selected Pod receives the RPC

The selected HTTP/2 subchannel sends the RPC to the selected Pod.

The application receives only the Customer result.

## 7. Pod failure

Suppose B becomes unavailable.

Kubernetes updates the EndpointSlice.

The discovery watch receives the update and rebuilds the aggregate endpoint set.

The resolver publishes the new list.

gRPC updates its subchannels.

Conceptually:

    A = ready
    B = not ready / removed
    C = ready

Subsequent RPCs are therefore sent through the remaining ready subchannels.

## 8. Pod replacement or migration

Suppose Kubernetes replaces B and gives the replacement a different IP.

Before:

    A = 10.0.1.10
    B = 10.0.1.11
    C = 10.0.1.12

After:

    A = 10.0.2.20
    C = 10.0.2.22
    D = 10.0.2.25

The application does nothing.

EndpointSlice changes.
The resolver publishes the new addresses.
gRPC removes obsolete subchannels and creates new ones as required.

The client does not permanently cache Pod IPs.

## 9. Retry behavior

The existing GrpcRetryProxy remains above the adapter.

For a retryable UNAVAILABLE:

    Attempt 1
        |
        v
    gRPC round_robin
        |
        v
    selected ready subchannel
        |
        v
    failure
        |
      backoff
        |
        v
    Attempt 2
        |
        v
    gRPC round_robin
        |
        v
    currently ready subchannel

The retry proxy controls:

- retryable status codes
- maximum total attempts
- initial delay
- exponential backoff
- maximum delay

The gRPC load balancer controls backend selection.

Important limitation:

Option B does not guarantee that a retry must use a different Pod from the failed attempt. round_robin distributes calls across ready subchannels, but it is not an application-level "exclude this endpoint for the rest of this RPC" policy.

If that exact behavior becomes mandatory, Option C is the appropriate architecture.

## 10. Retry configuration

Recommended starting configuration:

    grpc.retry.max-attempts=10
    grpc.retry.delay-millis=1000
    grpc.retry.backoff-multiplier=2.0
    grpc.retry.max-delay-millis=30000
    grpc.retry.retryable-statuses=UNAVAILABLE,DEADLINE_EXCEEDED,RESOURCE_EXHAUSTED

max-attempts is total attempts, including the first attempt.

Mutating RPCs should only be retried when duplicate execution is safe or an idempotency mechanism exists.

## 11. Reliability and discovery lifecycle

The discovery code intentionally keeps the complete EndpointSlice set rather than assuming one slice contains every Pod.

After a list, the resolver maintains a watch.

When a watch ends or fails, discovery performs another list and rebuilds the authoritative local slice set before watching again.

This is important because Kubernetes EndpointSlice state is dynamic and can span multiple slices.

The resolver also deduplicates address/port pairs before publishing them to gRPC.

## 12. Spring bean wiring

The application continues to inject:

    CustomerAdapterGrpc

The raw CustomerAdapterGrpcImpl should not be exposed as the application bean.

The intended bean graph is:

    GrpcKubernetesConfiguration
        |
        +-- ApiClient
        |
        +-- KubernetesNameResolverProvider
        |
        +-- gRPC NameResolverRegistry

    GrpcClientConfiguration
        |
        +-- GrpcRetryPolicy
        |
        +-- CustomerAdapterGrpcImpl
        |
        +-- GrpcRetryProxy
                |
                +-- customerAdapter
                        |
                        +-- CustomerAdapterGrpc

Existing application wiring can continue to refer to the bean name:

    customerAdapter

The application-facing type remains CustomerAdapterGrpc.

## 13. Spring properties

Example:

    grpc.kubernetes.in-cluster=true
    grpc.kubernetes.namespace=default
    grpc.services.customer.target=kube:///customer-service

    grpc.retry.max-attempts=10
    grpc.retry.delay-millis=1000
    grpc.retry.backoff-multiplier=2.0
    grpc.retry.max-delay-millis=30000
    grpc.retry.retryable-statuses=UNAVAILABLE,DEADLINE_EXCEEDED,RESOURCE_EXHAUSTED

For a local environment using the standard Kubernetes client configuration:

    grpc.kubernetes.in-cluster=false

## 14. Kubernetes RBAC

The client Pod needs permission to read EndpointSlices in the Service namespace.

Minimum permissions:

    apiGroups:
      - discovery.k8s.io
    resources:
      - endpointslices
    verbs:
      - get
      - list
      - watch

No write access to EndpointSlices is required.

## 15. Dependencies

The implementation targets Java 21.

It requires:

- gRPC Java
- Kubernetes Java client
- Spring
- SLF4J

Pin the Kubernetes Java client version in the application's dependency management rather than using a floating version.

The modern Kubernetes Java client line supports Java 11+, so Java 21 is appropriate.

## 16. What Option B owns

Our code owns:

- EndpointSlice API access
- EndpointSlice aggregation
- readiness filtering
- endpoint deduplication
- resolver updates
- watch/reconnect lifecycle
- application retry policy

gRPC owns:

- subchannels
- transport connections
- connection state
- round_robin
- selection among ready subchannels
- HTTP/2 transport

Kubernetes owns:

- Pod lifecycle
- Service membership
- EndpointSlice lifecycle
- readiness state
- Pod rescheduling
- Pod IP changes
- scaling and rolling replacement

The application owns:

- business operations
- adapter contract
- request/response handling

## 17. Why this is different from Option C

Option B does not maintain a custom endpoint-to-channel pool.

There is no custom round-robin selector.

There is no per-Pod failure table.

There is no client-side Pod IP cache.

The design deliberately lets gRPC do the connection and load-balancing work.

Option C remains useful if we later require:

- mandatory different-Pod retry
- per-Pod circuit breaking
- custom weights
- least-work selection
- zone-aware selection
- explicit endpoint exclusion
- application-controlled endpoint lifecycle

Option B is the simpler choice when the requirement is resilient distribution across the currently ready Kubernetes backends.

## 18. Verification target

Before production adoption, test the actual behavior with at least three backend Pods that return their identity.

Verify:

1. Repeated RPCs distribute across A, B, and C.
2. A failed Pod is removed from the usable endpoint set.
3. The remaining Pods continue serving RPCs.
4. A replacement Pod with a new IP becomes available without restarting the client.
5. A rolling deployment does not require application configuration changes.
6. A transient UNAVAILABLE is retried according to GrpcRetryPolicy.
7. The system recovers when the Kubernetes API watch is interrupted.

The most important acceptance criterion is not a permanently literal A-B-C sequence. It is reliable distribution across the currently ready backends and continued operation as Kubernetes changes the backend set.
