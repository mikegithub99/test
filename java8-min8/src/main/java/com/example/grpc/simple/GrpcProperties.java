package com.example.grpc.simple;

import io.grpc.Status;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

@ConfigurationProperties(prefix = "grpc")
public class GrpcProperties {
    private final Retry retry = new Retry();
    private final Map<String, Endpoint> services = new HashMap<>();

    public Retry getRetry() {
        return retry;
    }

    public Map<String, Endpoint> getServices() {
        return services;
    }

    public static class Retry {
        private int maxAttempts = 3;
        private long delayMillis = 100;
        private Set<Status.Code> retryableStatuses = EnumSet.of(
                Status.Code.UNAVAILABLE,
                Status.Code.DEADLINE_EXCEEDED,
                Status.Code.RESOURCE_EXHAUSTED);

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public long getDelayMillis() {
            return delayMillis;
        }

        public void setDelayMillis(long delayMillis) {
            this.delayMillis = delayMillis;
        }

        public Set<Status.Code> getRetryableStatuses() {
            return retryableStatuses;
        }

        public void setRetryableStatuses(Set<Status.Code> retryableStatuses) {
            this.retryableStatuses = retryableStatuses;
        }
    }

    public static class Endpoint {
        private String host;
        private int port;

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }
    }
}
