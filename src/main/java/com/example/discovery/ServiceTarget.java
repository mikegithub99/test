package com.example.discovery;
public record ServiceTarget(String serviceName, String instanceId, String target) {
  public String cacheKey() { return serviceName + "/" + ((instanceId == null || instanceId.isBlank()) ? target : instanceId); }
}