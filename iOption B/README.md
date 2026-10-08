# Option B — Kubernetes EndpointSlice + gRPC round_robin (Java 8)

This branch is the Java 8-compatible version of Option B.

## Java 8 compatibility

There is **no Java 21 requirement** in the Option B design.

gRPC-Java supports Java 8 and later.

The Kubernetes Java client changed its Java baseline starting with version 20.0.0. For Java 8 applications, use either:

- a pre-20 Java-8-compatible Kubernetes client release, or
- the Kubernetes client's `-legacy` artifact for 20.x.

For example:

    io.kubernetes:client-java:20.0.0-legacy

Do **not** use the normal `20.0.0` artifact on a Java 8 runtime.

Use Java 8 compilation:

    <properties>
        <maven.compiler.source>1.8</maven.compiler.source>
        <maven.compiler.target>1.8</maven.compiler.target>
    </properties>

The source in this branch uses Java 8-compatible language/API features only: lambdas, method references, `java.util.function`, streams, and Java 8 collections.

### Kubernetes client choices

For Kubernetes Java client 20.x:

    <dependency>
        <groupId>io.kubernetes</groupId>
        <artifactId>client-java</artifactId>
        <version>20.0.0-legacy</version>
    </dependency>

Or use a pinned pre-20 release that is known to support Java 8.

**Important:** keep the Kubernetes client version pinned. Do not allow an automatic dependency upgrade to move a Java 8 application to the normal 20.x+ SDK artifact.

### gRPC on Java 8

The gRPC dependency does not need a `-legacy` suffix.

For TLS deployments, verify the Java 8 update level. Older Java 8 releases have ALPN limitations; use a current supported Java 8 update and the normal gRPC transport recommendations.

This example uses:

    .usePlaintext()

Only use plaintext when that is acceptable for the application's network/security requirements.

## Architecture

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

## Kubernetes Service

This implementation is intended for a headless Service because the client is deliberately discovering individual Pod endpoints.

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

## Target

The adapter uses:

    kube:///customer-service

This is a logical target, not a Pod address.

The kube NameResolver extracts the Service name, asks the Kubernetes API for EndpointSlices in the configured namespace, and publishes ready endpoint IP/port pairs to gRPC.

## Classes

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
8. Reconnects after watch/list failures with bounded backoff.
9. Re-lists after a failed watch so state is rebuilt from Kubernetes.

### GrpcKubernetesConfiguration

Creates the Kubernetes ApiClient and registers the kube NameResolverProvider.

### GrpcClientConfiguration

Creates the retry policy and exposes the retrying `customerAdapter` bean.

### GrpcRetryProxy / GrpcRetryPolicy

The retry proxy owns retry count, status selection, and backoff. It does not own endpoint selection.

## Invocation sequence

Assume Kubernetes currently reports:

    Pod A = 10.0.1.10:50051
    Pod B = 10.0.1.11:50051
    Pod C = 10.0.1.12:50051

The application invokes:

    customerAdapter.getCustomer("123")

The retry proxy invokes CustomerAdapterGrpcImpl.

The adapter calls the generated blocking stub.

The ManagedChannel resolves:

    kube:///customer-service

EndpointSlice discovery publishes:

    10.0.1.10:50051
    10.0.1.11:50051
    10.0.1.12:50051

gRPC creates and maintains subchannels for those addresses.

With A, B, and C ready, `round_robin` is expected to distribute calls as:

    A -> B -> C -> A -> B -> C -> ...

This is an expected stable-state pattern, not a permanent guarantee. Reconnects, endpoint changes, startup timing, and failures can change the exact sequence.

## Pod failure and replacement

If B becomes unavailable:

1. Kubernetes updates the EndpointSlice.
2. The discovery watch receives the update.
3. The resolver publishes the new endpoint set.
4. gRPC updates its subchannels.
5. Subsequent RPCs use the remaining ready subchannels.

If Kubernetes replaces B with a Pod having a new IP, the application does nothing. EndpointSlice changes cause the resolver and gRPC channel to converge on the new endpoint set.

## Retry behavior

For a retryable `UNAVAILABLE`:

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

Recommended starting configuration:

    grpc.retry.max-attempts=10
    grpc.retry.delay-millis=1000
    grpc.retry.backoff-multiplier=2.0
    grpc.retry.max-delay-millis=30000
    grpc.retry.retryable-statuses=UNAVAILABLE,DEADLINE_EXCEEDED,RESOURCE_EXHAUSTED

`max-attempts` is total attempts, including the first attempt.

Option B does **not** guarantee that a retry uses a different Pod from the failed attempt. If mandatory endpoint exclusion is required, Option C is the better architecture.

Mutating RPCs should only be retried when duplicate execution is safe or an idempotency mechanism exists.

## Spring bean wiring

The application continues to inject:

    CustomerAdapterGrpc

The raw CustomerAdapterGrpcImpl should not be exposed as the application bean.

    GrpcKubernetesConfiguration
        |
        +-- ApiClient
        +-- KubernetesNameResolverProvider
        +-- gRPC NameResolverRegistry

    GrpcClientConfiguration
        |
        +-- GrpcRetryPolicy
        +-- CustomerAdapterGrpcImpl
        +-- GrpcRetryProxy
                |
                +-- customerAdapter
                        |
                        +-- CustomerAdapterGrpc

Existing application wiring can continue to refer to:

    customerAdapter

## Spring properties

    grpc.kubernetes.in-cluster=true
    grpc.kubernetes.namespace=default
    grpc.services.customer.target=kube:///customer-service

    grpc.retry.max-attempts=10
    grpc.retry.delay-millis=1000
    grpc.retry.backoff-multiplier=2.0
    grpc.retry.max-delay-millis=30000
    grpc.retry.retryable-statuses=UNAVAILABLE,DEADLINE_EXCEEDED,RESOURCE_EXHAUSTED

For a local environment:

    grpc.kubernetes.in-cluster=false

## Kubernetes RBAC

The client Pod needs:

    apiGroups:
      - discovery.k8s.io
    resources:
      - endpointslices
    verbs:
      - get
      - list
      - watch

No write access is required.

## Dependencies

Required:

- gRPC Java
- Kubernetes Java client
- Spring
- SLF4J

For Java 8, the key dependency rule is:

    Java 8
       |
       +-- gRPC Java: normal Java 8-compatible release
       |
       +-- Kubernetes client:
              +-- pre-20 Java 8-compatible release
              OR
              +-- 20.x -legacy artifact

## What Option B owns

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

## Option B vs Option C

Option B does not maintain a custom endpoint-to-channel pool.

There is no custom round-robin selector, per-Pod failure table, or client-side Pod IP cache.

Option C becomes useful if we later require:

- mandatory different-Pod retry
- per-Pod circuit breaking
- custom weights
- least-work selection
- zone-aware selection
- explicit endpoint exclusion
- application-controlled endpoint lifecycle

## Verification target

Before production adoption, test with at least three backend Pods that return their identity.

Verify:

1. Repeated RPCs distribute across A, B, and C.
2. A failed Pod is removed from the usable endpoint set.
3. Remaining Pods continue serving RPCs.
4. A replacement Pod with a new IP becomes available without restarting the client.
5. A rolling deployment does not require application configuration changes.
6. A transient UNAVAILABLE is retried according to GrpcRetryPolicy.
7. The system recovers when the Kubernetes API watch is interrupted.

The key acceptance criterion is reliable distribution across currently ready backends and continued operation as Kubernetes changes the backend set.
