package com.example.grpc.simple;

import io.grpc.Status;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@ConfigurationProperties(prefix = "grpc")
public class GrpcProperties {
    private Retry retry = new Retry();
    private Map<String, Endpoint> services = new HashMap<String, Endpoint>();

    public Retry getRetry() { return retry; }
    public void setRetry(Retry retry) { this.retry = retry; }
    public Map<String, Endpoint> getServices() { return services; }
    public void setServices(Map<String, Endpoint> services) { this.services = services; }

    public static class Endpoint {
        private String host;
        private int port;
        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }
        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
    }

    public static class Retry {
        private int maxAttempts = 3;
        private long delayMillis = 100;
        private List<Status.Code> retryableStatuses = new ArrayList<Status.Code>(
                EnumSet.of(Status.Code.UNAVAILABLE, Status.Code.DEADLINE_EXCEEDED, Status.Code.RESOURCE_EXHAUSTED));

        public int getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
        public long getDelayMillis() { return delayMillis; }
        public void setDelayMillis(long delayMillis) { this.delayMillis = delayMillis; }
        public List<Status.Code> getRetryableStatuses() { return retryableStatuses; }
        public void setRetryableStatuses(List<Status.Code> retryableStatuses) { this.retryableStatuses = retryableStatuses; }

        public GrpcRetryPolicy toPolicy() {
            Set<Status.Code> statuses = EnumSet.copyOf(retryableStatuses);
            return new GrpcRetryPolicy(maxAttempts, delayMillis, statuses);
        }
    }
}
