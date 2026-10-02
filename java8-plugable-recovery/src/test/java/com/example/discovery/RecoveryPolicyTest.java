package com.example.discovery;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.Assert;
import org.junit.Test;

public class RecoveryPolicyTest {
    @Test public void registryReplacesUnavailable() {
        Assert.assertEquals(RecoveryAction.REPLACE_CLIENT, new RegistryRecoveryPolicy().decide(context(Status.UNAVAILABLE)));
    }
    @Test public void registryReplacesDeadlineExceeded() {
        Assert.assertEquals(RecoveryAction.REPLACE_CLIENT, new RegistryRecoveryPolicy().decide(context(Status.DEADLINE_EXCEEDED)));
    }
    @Test public void registryFailsInvalidArgument() {
        Assert.assertEquals(RecoveryAction.FAIL, new RegistryRecoveryPolicy().decide(context(Status.INVALID_ARGUMENT)));
    }
    @Test public void kubernetesKeepsCurrentForUnavailable() {
        Assert.assertEquals(RecoveryAction.RETRY_CURRENT, new KubernetesRecoveryPolicy().decide(context(Status.UNAVAILABLE)));
    }
    @Test public void kubernetesKeepsCurrentForDeadlineExceeded() {
        Assert.assertEquals(RecoveryAction.RETRY_CURRENT, new KubernetesRecoveryPolicy().decide(context(Status.DEADLINE_EXCEEDED)));
    }
    @Test public void kubernetesFailsInvalidArgument() {
        Assert.assertEquals(RecoveryAction.FAIL, new KubernetesRecoveryPolicy().decide(context(Status.INVALID_ARGUMENT)));
    }
    private static RecoveryContext context(Status status) {
        return new RecoveryContext("CUSTOMER_SERVICE", null, new StatusRuntimeException(status), 1, 3);
    }
}
