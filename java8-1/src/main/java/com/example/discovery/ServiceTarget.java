package com.example.discovery;

public final class ServiceTarget {
    private final String serviceName;
    private final String instanceId;
    private final String target;

    public ServiceTarget(String serviceName, String instanceId, String target) {
        this.serviceName = requireText(serviceName, "serviceName");
        this.instanceId = instanceId;
        this.target = requireText(target, "target");
    }

    public String serviceName() { return serviceName; }
    public String instanceId() { return instanceId; }
    public String target() { return target; }

    public String cacheKey() {
        return serviceName + "/" +
                ((instanceId == null || instanceId.trim().isEmpty())
                        ? target : instanceId);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
