package com.example.discovery;

public interface RecoveryPolicy {
    RecoveryAction decide(RecoveryContext context);
}
