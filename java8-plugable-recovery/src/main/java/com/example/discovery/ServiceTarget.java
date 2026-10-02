package com.example.discovery;

public final class ServiceTarget {
    private final String serviceName;
    private final String instanceId;
    private final String target;

    public ServiceTarget(String serviceName, String instanceId, String target) {
        if (serviceName == null || serviceName.trim().isEmpty()) {
            throw new IllegalArgumentException("serviceName must not be blank");
        }
        if (target == null || target.trim().isEmpty()) {
            throw new IllegalArgumentException("target must not be blank");
        }
        this.serviceName = serviceName;
        this.instanceId = instanceId;
        this.target = target;
    }

    public String serviceName() { return serviceName; }
    public String instanceId() { return instanceId; }
    public String target() { return target; }

    public String cacheKey() {
        if (instanceId != null && !instanceId.trim().isEmpty()) {
            return serviceName + "/" + instanceId;
        }
        return serviceName + "/" + target;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof ServiceTarget)) return false;
        ServiceTarget that = (ServiceTarget) other;
        return serviceName.equals(that.serviceName)
                && equalsNullable(instanceId, that.instanceId)
                && target.equals(that.target);
    }

    @Override
    public int hashCode() {
        int result = serviceName.hashCode();
        result = 31 * result + (instanceId == null ? 0 : instanceId.hashCode());
        return 31 * result + target.hashCode();
    }

    private static boolean equalsNullable(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}
