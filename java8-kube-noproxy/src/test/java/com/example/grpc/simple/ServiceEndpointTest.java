package com.example.grpc.simple;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ServiceEndpointTest {
    @Test
    void hostPort() {
        assertEquals("server-a:50051",
                ServiceEndpoint.hostPort("CUSTOMER_SERVICE", "server-a", 50051).getTarget());
    }

    @Test
    void invalidPort() {
        assertThrows(IllegalArgumentException.class,
                () -> ServiceEndpoint.hostPort("CUSTOMER_SERVICE", "server-a", 0));
    }
}
