package com.example.discovery;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class StaticServiceDiscovery implements ServiceDiscovery {
    private final Map<String, List<ServiceTarget>> services;
    private final ConcurrentMap<String, AtomicInteger> counters =
            new ConcurrentHashMap<String, AtomicInteger>();

    public StaticServiceDiscovery(Map<String, List<ServiceTarget>> services) {
        if (services == null) {
            throw new IllegalArgumentException("services must not be null");
        }

        Map<String, List<ServiceTarget>> copy =
                new LinkedHashMap<String, List<ServiceTarget>>();

        for (Map.Entry<String, List<ServiceTarget>> entry : services.entrySet()) {
            if (entry.getKey() == null || entry.getKey().trim().isEmpty()) {
                throw new IllegalArgumentException("service name must not be blank");
            }
            List<ServiceTarget> targets = entry.getValue();
            if (targets == null || targets.isEmpty()) {
                throw new IllegalArgumentException(
                        "No targets configured for " + entry.getKey());
            }
            copy.put(
                    entry.getKey(),
                    Collections.unmodifiableList(
                            new ArrayList<ServiceTarget>(targets)));
        }

        this.services = Collections.unmodifiableMap(copy);
    }

    @Override
    public ServiceTarget lookup(
            String serviceName,
            Set<String> excludedInstanceIds) {

        List<ServiceTarget> configured = services.get(serviceName);
        if (configured == null) {
            throw new IllegalStateException(
                    "No service configured for " + serviceName);
        }

        List<ServiceTarget> eligible = new ArrayList<ServiceTarget>();
        for (ServiceTarget target : configured) {
            if (excludedInstanceIds == null
                    || !excludedInstanceIds.contains(target.instanceId())) {
                eligible.add(target);
            }
        }

        if (eligible.isEmpty()) {
            throw new IllegalStateException(
                    "No instance available for " + serviceName);
        }

        AtomicInteger counter = counters.get(serviceName);
        if (counter == null) {
            AtomicInteger created = new AtomicInteger(0);
            AtomicInteger existing = counters.putIfAbsent(serviceName, created);
            counter = existing == null ? created : existing;
        }

        int index = Math.floorMod(
                counter.getAndIncrement(), eligible.size());

        return eligible.get(index);
    }
}
