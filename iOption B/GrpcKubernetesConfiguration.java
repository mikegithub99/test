package com.example.grpc.simple.config;

import com.example.grpc.simple.kubernetes.KubernetesNameResolver;
import com.example.grpc.simple.kubernetes.KubernetesNameResolverProvider;
import io.grpc.NameResolverRegistry;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.util.ClientBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring wiring for the Option B Kubernetes resolver.
 */
@Configuration
public class GrpcKubernetesConfiguration {
    @Bean
    public NameResolverRegistry grpcNameResolverRegistry() {
        return NameResolverRegistry.getDefaultRegistry();
    }

    @Bean
    public ApiClient kubernetesApiClient(
            @Value("\${grpc.kubernetes.in-cluster:true}") boolean inCluster) throws Exception {
        return inCluster ? ClientBuilder.cluster().build() : ClientBuilder.standard().build();
    }

    @Bean
    public KubernetesNameResolverProvider kubernetesNameResolverProvider(
            ApiClient apiClient,
            @Value("\${grpc.kubernetes.namespace:default}") String namespace) {
        KubernetesNameResolverProvider provider =
                new KubernetesNameResolverProvider(
                        new KubernetesNameResolver.Factory(apiClient, namespace));
        NameResolverRegistry.getDefaultRegistry().register(provider);
        return provider;
    }
}
