# Bare-Kubernetes sample: Java 8 gRPC client + C++ customer servers

This directory is a complete Kubernetes manifest set for exercising **Option B**:

- Kubernetes owns Pod lifecycle and Service membership.
- EndpointSlices expose the current backend Pod endpoints.
- The Java 8 client watches EndpointSlices.
- The client publishes Pod addresses to a gRPC NameResolver.
- gRPC `round_robin` balances calls across ready backend subchannels.
- The retry proxy retries bounded transient failures.
- No Consul, Envoy, xDS, or custom client-side endpoint pool is required.

Kubernetes creates and maintains EndpointSlices automatically for a Service with a selector. The client should therefore **not** create EndpointSlice objects itself. urlKubernetes EndpointSlices documentationhttps://kubernetes.io/docs/concepts/services-networking/endpoint-slices/

## Directory

| File | Purpose |
|---|---|
| `00-namespace.yaml` | Creates isolated `grpc-demo` namespace |
| `10-customer-server-deployment.yaml` | Runs 3 C++ customer-server Pods |
| `20-customer-service.yaml` | Headless `customer-service` on gRPC port 50051 |
| `30-client-serviceaccount.yaml` | ServiceAccount for the Java client |
| `31-client-rbac.yaml` | Least-privilege EndpointSlice get/list/watch |
| `40-client-configmap.yaml` | Java client Kubernetes target/retry configuration |
| `41-client-deployment.yaml` | Runs the Java 8 customer client |
| `50-kustomization.yaml` | Applies the complete sample set |

## 1. Required images

The manifests intentionally use placeholder image names:

    example/customer-server:latest
    example/customer-client:latest

Replace these with the images containing your actual sample applications.

For a local **kind** cluster:

    kind load docker-image <customer-server-image>
    kind load docker-image <customer-client-image>

For **minikube**:

    minikube image load <customer-server-image>
    minikube image load <customer-client-image>

For a normal remote cluster, push the images to a registry accessible by the cluster and change the image names in the manifests or Kustomization.

The Kubernetes manifests do not build application images.

## 2. Server requirements

The customer server image must:

- listen on TCP port 50051
- implement the sample Customer gRPC service
- remain running as a long-lived gRPC server
- return a useful response containing server identity if you want to observe round-robin behavior

The Deployment exposes port 50051 and uses a TCP readiness probe. This is deliberately simple and works without adding a separate health-check protocol.

## 3. Client requirements

The customer client image must contain the Java 8 Option B implementation from `iOption B`.

The client must use the in-cluster Kubernetes client configuration and the target:

    kube:///customer-service

The Pod's ServiceAccount is granted only:

    discovery.k8s.io/endpointslices:
      get
      list
      watch

The Kubernetes ServiceAccount/RBAC pattern follows Kubernetes' least-privilege model. urlKubernetes Service Accounts documentationhttps://kubernetes.io/docs/concepts/security/service-accounts/

## 4. Deploy from a bare Kubernetes cluster

Create the namespace and all resources:

    kubectl apply -k kube-deploy-samplesetvice/

Or without Kustomize:

    kubectl apply -f kube-deploy-samplesetvice/00-namespace.yaml
    kubectl apply -f kube-deploy-samplesetvice/10-customer-server-deployment.yaml
    kubectl apply -f kube-deploy-samplesetvice/20-customer-service.yaml
    kubectl apply -f kube-deploy-samplesetvice/30-client-serviceaccount.yaml
    kubectl apply -f kube-deploy-samplesetvice/31-client-rbac.yaml
    kubectl apply -f kube-deploy-samplesetvice/40-client-configmap.yaml
    kubectl apply -f kube-deploy-samplesetvice/41-client-deployment.yaml

The order is intentionally namespace -> server -> Service -> client identity/RBAC -> client configuration -> client.

## 5. Verify the server

    kubectl -n grpc-demo get pods -o wide
    kubectl -n grpc-demo get deployment customer-server
    kubectl -n grpc-demo get service customer-service

You should see three customer-server Pods.

Check the EndpointSlices:

    kubectl -n grpc-demo get endpointslices -l kubernetes.io/service-name=customer-service

Then inspect the actual backend addresses:

    kubectl -n grpc-demo describe endpointslice

EndpointSlices are the Kubernetes source used by this Option B resolver. A Service can have multiple EndpointSlices, and consumers must combine them to obtain the complete backend set. urlKubernetes EndpointSlice API documentationhttps://kubernetes.io/docs/reference/kubernetes-api/discovery/endpoint-slice-v1/

## 6. Verify the client

    kubectl -n grpc-demo get deployment customer-client
    kubectl -n grpc-demo get pods -l app=customer-client
    kubectl -n grpc-demo logs deployment/customer-client

The client should start with:

    grpc.kubernetes.in-cluster=true
    grpc.kubernetes.namespace=grpc-demo
    grpc.services.customer.target=kube:///customer-service

The exact application log messages depend on the sample client implementation.

## 7. Verify RBAC

Confirm the client ServiceAccount can read EndpointSlices:

    kubectl auth can-i get endpointslices       --as=system:serviceaccount:grpc-demo:customer-client       -n grpc-demo

    kubectl auth can-i list endpointslices       --as=system:serviceaccount:grpc-demo:customer-client       -n grpc-demo

    kubectl auth can-i watch endpointslices       --as=system:serviceaccount:grpc-demo:customer-client       -n grpc-demo

All three should return:

    yes

It should not have write access:

    kubectl auth can-i create endpointslices       --as=system:serviceaccount:grpc-demo:customer-client       -n grpc-demo

Expected:

    no

## 8. Observe round-robin

Run enough client calls to observe backend identity.

With three healthy Pods:

    customer-server-A
    customer-server-B
    customer-server-C

a stable set of ready gRPC subchannels should produce behavior consistent with:

    A -> B -> C -> A -> B -> C -> ...

Do not treat that sequence as an absolute invariant. gRPC round_robin operates over the currently ready subchannels. Startup timing, reconnects, Pod failures, and endpoint changes can change the exact order.

## 9. Test Pod failure

Find a customer-server Pod:

    kubectl -n grpc-demo get pods -l app=customer-server

Delete one:

    kubectl -n grpc-demo delete pod <customer-server-pod>

Watch the replacement:

    kubectl -n grpc-demo get pods -l app=customer-server -w

Watch EndpointSlices:

    kubectl -n grpc-demo get endpointslices       -l kubernetes.io/service-name=customer-service -w

The expected flow is:

    Pod deleted
        ->
    EndpointSlice changes
        ->
    Java resolver receives watch event
        ->
    resolver publishes new address list
        ->
    gRPC updates subchannels
        ->
    remaining ready Pods continue serving

The client should not need a restart or a changed target.

## 10. Test Pod migration / replacement

Force a rollout:

    kubectl -n grpc-demo rollout restart deployment/customer-server

Watch:

    kubectl -n grpc-demo rollout status deployment/customer-server

and:

    kubectl -n grpc-demo get endpointslices       -l kubernetes.io/service-name=customer-service -w

The Service name remains:

    customer-service

The client target remains:

    kube:///customer-service

Only the EndpointSlice membership changes.

## 11. Test scaling

Scale from three to five servers:

    kubectl -n grpc-demo scale deployment/customer-server --replicas=5

Verify:

    kubectl -n grpc-demo get pods -l app=customer-server
    kubectl -n grpc-demo get endpointslices -l kubernetes.io/service-name=customer-service

The resolver should discover the new endpoints without client configuration changes.

Scale back:

    kubectl -n grpc-demo scale deployment/customer-server --replicas=3

## 12. Retry test

The ConfigMap starts with:

    grpc.retry.max-attempts=10
    grpc.retry.delay-millis=1000
    grpc.retry.backoff-multiplier=2.0
    grpc.retry.max-delay-millis=30000
    grpc.retry.retryable-statuses=UNAVAILABLE,DEADLINE_EXCEEDED,RESOURCE_EXHAUSTED

A retry is a new RPC attempt through the same long-lived gRPC channel. Endpoint selection remains owned by gRPC round_robin.

Option B does not guarantee that a retry avoids the exact Pod that failed the previous attempt.

## 13. Important headless-Service detail

This sample uses:

    clusterIP: None

because Option B intentionally discovers individual backend Pod addresses.

The Service itself remains the stable logical identity:

    customer-service

The EndpointSlice controller maintains the actual backend endpoints selected by:

    app: customer-server

Kubernetes documents EndpointSlices as the modern Service endpoint API; the older Endpoints API is deprecated. urlKubernetes Service documentationhttps://kubernetes.io/docs/concepts/services-networking/service/

## 14. What this sample does not require

This deployment does **not** require:

- Consul
- Envoy
- xDS
- an external discovery server
- a custom Java endpoint pool
- manually-created EndpointSlices
- Pod IPs in application configuration

## 15. Cleanup

Delete the entire sample:

    kubectl delete -k kube-deploy-samplesetvice/

Or delete the namespace:

    kubectl delete namespace grpc-demo

Deleting the namespace removes the sample resources contained within it.

## 16. Production notes

This is a deployment sample, not a production security baseline.

Before production use:

- use immutable application image tags rather than `latest`
- use a private registry and image pull credentials as required
- use TLS/mTLS when required by the environment
- configure CPU/memory based on actual measurements
- consider PodDisruptionBudget and topology spread constraints
- use a real gRPC health/readiness check if TCP-open is insufficient
- review ServiceAccount token mounting and namespace security
- use NetworkPolicies where appropriate
- keep the Kubernetes Java client on a pinned Java-8-compatible release

The client only needs read access to EndpointSlices; it does not need permission to modify Kubernetes resources.
