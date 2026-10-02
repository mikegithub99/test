package com.example.discovery;

import io.grpc.ManagedChannel;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class GrpcClient {
    private final ServiceTarget target;
    private final ManagedChannel channel;
    private final Object delegate;
    private final AtomicInteger inFlight = new AtomicInteger(0);
    private final AtomicBoolean retired = new AtomicBoolean(false);

    public GrpcClient(ServiceTarget target, ManagedChannel channel, Object delegate) {
        if (target == null || channel == null || delegate == null) {
            throw new IllegalArgumentException("target, channel and delegate are required");
        }
        this.target = target;
        this.channel = channel;
        this.delegate = delegate;
    }

    public ServiceTarget target() { return target; }
    public ManagedChannel channel() { return channel; }

    @SuppressWarnings("unchecked")
    public <T> T delegate() { return (T) delegate; }

    public boolean tryAcquire() {
        for (;;) {
            if (retired.get()) return false;
            int current = inFlight.get();
            if (inFlight.compareAndSet(current, current + 1)) {
                if (!retired.get()) return true;
                release();
                return false;
            }
        }
    }

    public void release() {
        int remaining = inFlight.decrementAndGet();
        if (remaining < 0) throw new IllegalStateException("Client lease underflow");
        if (remaining == 0 && retired.get()) channel.shutdown();
    }

    public void retire() {
        if (retired.compareAndSet(false, true) && inFlight.get() == 0) {
            channel.shutdown();
        }
    }

    public boolean isRetired() { return retired.get(); }
    public int inFlight() { return inFlight.get(); }
}
