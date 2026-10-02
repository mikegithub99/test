package com.example.discovery;
import org.junit.Assert;
import org.junit.Test;

public class ServiceTargetTest {
    @Test public void targetEqualityIncludesInstanceIdentity() {
        ServiceTarget a = new ServiceTarget("CUSTOMER_SERVICE", "instance-1", "server-a:50051");
        ServiceTarget b = new ServiceTarget("CUSTOMER_SERVICE", "instance-1", "server-a:50051");
        ServiceTarget c = new ServiceTarget("CUSTOMER_SERVICE", "instance-2", "server-a:50051");
        Assert.assertEquals(a, b);
        Assert.assertNotEquals(a, c);
    }
}
