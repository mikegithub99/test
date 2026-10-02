package com.example.discovery;

public final class ServiceTarget {
    private final String serviceName;
    private final String instanceId;
    private final String target;

    public ServiceTarget(String serviceName, String instanceId, String target) {
        this.serviceName = serviceName;
        this.instanceId = instanceId;
        this.target = target;
    }

    public String serviceName() { return serviceName; }
    public String instanceId() { return instanceId; }
    public String target() { return target; }

    public String cacheKey() {
        return serviceName + "/" +
                ((instanceId == null || instanceId.trim().isEmpty())
                        ? target : instanceId);
    }
}
