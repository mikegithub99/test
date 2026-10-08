package com.example.grpc.simple.kubernetes;

import com.google.gson.reflect.TypeToken;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.DiscoveryV1Api;
import io.kubernetes.client.openapi.models.V1Endpoint;
import io.kubernetes.client.openapi.models.V1EndpointPort;
import io.kubernetes.client.openapi.models.V1EndpointSlice;
import io.kubernetes.client.openapi.models.V1EndpointSliceList;
import io.kubernetes.client.util.Watch;
import okhttp3.Call;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Low-level Kubernetes EndpointSlice discovery for one Service.
 *
 * Performs an initial list, maintains all slices for the Service, watches
 * changes, aggregates ready endpoint addresses, and reconnects after errors.
 */
public final class KubernetesEndpointSliceDiscovery implements AutoCloseable {
    private static final Logger log =
            LoggerFactory.getLogger(KubernetesEndpointSliceDiscovery.class);
    private static final String SERVICE_LABEL = "kubernetes.io/service-name";
    private static final int WATCH_TIMEOUT_SECONDS = 300;

    private final DiscoveryV1Api discoveryApi;
    private final ApiClient apiClient;
    private final String namespace;
    private final String serviceName;
    private final Consumer<List<SocketAddress>> listener;
    private final ExecutorService executor;
    private final Map<String, V1EndpointSlice> slices = new HashMap<>();

    private volatile boolean running;

    public KubernetesEndpointSliceDiscovery(
            ApiClient apiClient,
            String namespace,
            String serviceName,
            Consumer<List<SocketAddress>> listener) {
        this.apiClient = apiClient;
        this.discoveryApi = new DiscoveryV1Api(apiClient);
        this.namespace = namespace;
        this.serviceName = serviceName;
        this.listener = listener;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(
                    r, "grpc-kube-endpoint-slice-" + serviceName);
            thread.setDaemon(true);
            return thread;
        });
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        executor.submit(this::run);
    }

    public synchronized void refresh() {
        if (!running) {
            return;
        }
        executor.submit(() -> {
            try {
                String resourceVersion = initialList();
                watch(resourceVersion);
            } catch (Exception e) {
                log.debug("EndpointSlice refresh failed for {}/{}",
                        namespace, serviceName, e);
            }
        });
    }

    private void run() {
        long backoffMillis = 1000L;
        while (running) {
            try {
                String resourceVersion = initialList();
                backoffMillis = 1000L;
                watch(resourceVersion);
            } catch (Exception e) {
                if (!running) {
                    return;
                }
                log.warn(
                        "EndpointSlice discovery failed for {}/{}; retrying in {} ms",
                        namespace, serviceName, backoffMillis, e);
                sleep(backoffMillis);
                backoffMillis = Math.min(backoffMillis * 2L, 30000L);
            }
        }
    }

    private String initialList() throws ApiException {
        V1EndpointSliceList list = discoveryApi.listNamespacedEndpointSlice(
                namespace, null, true, null, null,
                SERVICE_LABEL + "=" + serviceName,
                null, null, null, null, null, false);

        synchronized (slices) {
            slices.clear();
            if (list.getItems() != null) {
                for (V1EndpointSlice slice : list.getItems()) {
                    String name = slice.getMetadata() == null
                            ? null : slice.getMetadata().getName();
                    if (name != null) {
                        slices.put(name, slice);
                    }
                }
            }
        }

        publishEndpoints();
        return list.getMetadata() == null
                ? null : list.getMetadata().getResourceVersion();
    }

    private void watch(String resourceVersion) throws Exception {
        Call call = discoveryApi.listNamespacedEndpointSliceCall(
                namespace, null, true, null, null,
                SERVICE_LABEL + "=" + serviceName,
                null, resourceVersion, null, null,
                WATCH_TIMEOUT_SECONDS, true, null);

        Watch<V1EndpointSlice> watch = Watch.createWatch(
                apiClient, call,
                new TypeToken<Watch.Response<V1EndpointSlice>>() { }.getType());

        try {
            for (Watch.Response<V1EndpointSlice> event : watch) {
                if (!running || event == null || event.object == null) {
                    return;
                }

                String type = event.type == null ? "" : event.type;
                V1EndpointSlice slice = event.object;
                String name = slice.getMetadata() == null
                        ? null : slice.getMetadata().getName();

                if ("ADDED".equals(type) || "MODIFIED".equals(type)) {
                    if (name != null) {
                        synchronized (slices) {
                            slices.put(name, slice);
                        }
                        publishEndpoints();
                    }
                } else if ("DELETED".equals(type)) {
                    if (name != null) {
                        synchronized (slices) {
                            slices.remove(name);
                        }
                        publishEndpoints();
                    }
                } else if ("ERROR".equals(type)) {
                    throw new IllegalStateException(
                            "EndpointSlice watch returned an ERROR event");
                }
            }
        } finally {
            watch.close();
        }
    }

    private void publishEndpoints() {
        Set<SocketAddress> endpoints = new LinkedHashSet<>();

        synchronized (slices) {
            for (V1EndpointSlice slice : slices.values()) {
                Integer port = findGrpcPort(slice.getPorts());
                if (port == null || slice.getEndpoints() == null) {
                    continue;
                }

                for (V1Endpoint endpoint : slice.getEndpoints()) {
                    if (!isReady(endpoint) || endpoint.getAddresses() == null) {
                        continue;
                    }
                    for (String address : endpoint.getAddresses()) {
                        endpoints.add(new InetSocketAddress(address, port));
                    }
                }
            }
        }

        listener.accept(Collections.unmodifiableList(
                new ArrayList<>(endpoints)));
    }

    private Integer findGrpcPort(List<V1EndpointPort> ports) {
        if (ports == null) {
            return null;
        }
        for (V1EndpointPort port : ports) {
            if (port.getPort() != null && "grpc".equals(port.getName())) {
                return port.getPort();
            }
        }
        for (V1EndpointPort port : ports) {
            if (port.getPort() != null) {
                return port.getPort();
            }
        }
        return null;
    }

    private boolean isReady(V1Endpoint endpoint) {
        if (endpoint.getConditions() == null) {
            return true;
        }
        Boolean ready = endpoint.getConditions().getReady();
        Boolean serving = endpoint.getConditions().getServing();

        if (Boolean.TRUE.equals(ready)) {
            return true;
        }
        return ready == null && Boolean.TRUE.equals(serving);
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public synchronized void close() {
        running = false;
        executor.shutdownNow();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
