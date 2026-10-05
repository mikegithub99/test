package com.example.grpc.simple;

public class ServiceEndpoint {
    private final String host;
    private final int port;
    public ServiceEndpoint(String host, int port) { this.host = host; this.port = port; }
    public String getHost() { return host; }
    public int getPort() { return port; }
}