package com.example.discovery;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Set;

public final class StaticServiceDiscovery implements ServiceDiscovery {
    private final Map<String, List<ServiceTarget>> services;
    private final ConcurrentMap<String, AtomicInteger> counters =
            new ConcurrentHashMap<String, AtomicInteger>();

    public StaticServiceDiscovery(Map<String, List<ServiceTarget>> services) {
        this.services = Collections.unmodifiableMap(services);
    }

    @Override
    public ServiceTarget lookup(
            String serviceName,
            Set<String> excludedInstanceIds) {

        List<ServiceTarget> configured = services.get(serviceName);
        List<ServiceTarget> eligible = new ArrayList<ServiceTarget>();

        if (configured != null) {
            for (ServiceTarget target : configured) {
                if (!excludedInstanceIds.contains(target.instanceId())) {
                    eligible.add(target);
                }
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
