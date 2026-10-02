package com.example.discovery;

import io.grpc.Status;

public final class KubernetesRecoveryPolicy implements RecoveryPolicy {
    @Override
    public RecoveryAction decide(RecoveryContext context) {
        Status.Code code = context.failure().getStatus().getCode();
        if (code == Status.Code.UNAVAILABLE
                || code == Status.Code.DEADLINE_EXCEEDED
                || code == Status.Code.RESOURCE_EXHAUSTED) {
            return RecoveryAction.RETRY_CURRENT;
        }
        return RecoveryAction.FAIL;
    }
}
