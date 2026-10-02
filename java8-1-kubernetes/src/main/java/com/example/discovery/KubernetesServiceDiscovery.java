package com.example.discovery;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Kubernetes-native discovery using a Kubernetes Service DNS name as the
 * stable endpoint.
 *
 * Normal Kubernetes Service discovery does not expose individual pod identity.
 * Excluded instance IDs are accepted for compatibility but are not used for
 * pod selection. Exact instance exclusion can be added later with an
 * EndpointSlice-aware implementation behind ServiceDiscovery.
 */
public final class KubernetesServiceDiscovery implements ServiceDiscovery {

    private final String namespace;
    private final String clusterDomain;
    private final Map<String, KubernetesService> services;

    public KubernetesServiceDiscovery(
            String namespace,
            String clusterDomain,
            Map<String, KubernetesService> services) {

        this.namespace = requireText(namespace, "namespace");
        this.clusterDomain = requireText(clusterDomain, "clusterDomain");

        if (services == null || services.isEmpty()) {
            throw new IllegalArgumentException("services must not be null or empty");
        }

        Map<String, KubernetesService> copy = new HashMap<String, KubernetesService>();
        for (Map.Entry<String, KubernetesService> entry : services.entrySet()) {
            String serviceName = requireText(entry.getKey(), "serviceName");
            if (entry.getValue() == null) {
                throw new IllegalArgumentException(
                        "service definition must not be null: " + serviceName);
            }
            copy.put(serviceName, entry.getValue());
        }

        this.services = Collections.unmodifiableMap(copy);
    }

    public KubernetesServiceDiscovery(
            String namespace,
            Map<String, KubernetesService> services) {
        this(namespace, "cluster.local", services);
    }

    @Override
    public ServiceTarget lookup(
            String serviceName,
            Set<String> excludedInstanceIds) {

        KubernetesService service = services.get(serviceName);
        if (service == null) {
            throw new IllegalStateException(
                    "No Kubernetes service configured for: " + serviceName);
        }

        String dnsName = service.dnsName(namespace, clusterDomain);
        return new ServiceTarget(
                serviceName,
                serviceName,
                dnsName + ":" + service.port());
    }

    private static String requireText(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    public static final class KubernetesService {
        private final String host;
        private final int port;

        public KubernetesService(String host, int port) {
            this.host = requireText(host, "host");
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException(
                        "port must be between 1 and 65535");
            }
            this.port = port;
        }

        public String host() {
            return host;
        }

        public int port() {
            return port;
        }

        private String dnsName(String namespace, String clusterDomain) {
            if (host.indexOf('.') >= 0) {
                return host;
            }
            return host + "." + namespace + ".svc." + clusterDomain;
        }
    }
}
