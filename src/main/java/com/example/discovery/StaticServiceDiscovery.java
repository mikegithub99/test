package com.example.discovery;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
public final class StaticServiceDiscovery implements ServiceDiscovery {
  private final Map<String,List<ServiceTarget>> services;
  private final ConcurrentMap<String,AtomicInteger> counters = new ConcurrentHashMap<>();
  public StaticServiceDiscovery(Map<String,List<ServiceTarget>> services) { this.services = Map.copyOf(services); }
  public ServiceTarget lookup(String serviceName, Set<String> excluded) {
    List<ServiceTarget> eligible = services.getOrDefault(serviceName,List.of()).stream().filter(t -> !excluded.contains(t.instanceId())).toList();
    if (eligible.isEmpty()) throw new IllegalStateException("No instance available for " + serviceName);
    int i = counters.computeIfAbsent(serviceName,k -> new AtomicInteger()).getAndIncrement();
    return eligible.get(Math.floorMod(i,eligible.size()));
  }
}