package com.example.discovery;
import java.util.Set;
public interface ServiceDiscovery { ServiceTarget lookup(String serviceName, Set<String> excludedInstanceIds); }