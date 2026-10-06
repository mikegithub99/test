package com.example.grpc.simple;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(GrpcProperties.class)
public class GrpcClientConfiguration {

    @Bean
    public CustomerApi customerApi(GrpcProperties properties) {
        GrpcProperties.Endpoint endpoint = properties.getServices().get("customer");
        if (endpoint == null) {
            throw new IllegalStateException("Missing grpc.services.customer configuration");
        }

        CustomerAdapterGrpc adapter = new CustomerAdapterGrpc(
                endpoint.getHost(), endpoint.getPort());

        return GrpcProxy.create(
                adapter,
                CustomerApi.class,
                properties.getRetry().toPolicy());
    }
}
