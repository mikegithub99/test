# Standalone RedisClient singleton example

This repository example demonstrates a C++17 lazy, process-wide `RedisClient` accessor without an ELF registration hook or factory.

## Usage

```cpp
#include "redis_client_singleton.hpp"

int main() {
    app::RedisClient::Configure({
        "redis://127.0.0.1:6379",
        false // is_cluster
    });

    auto redis = app::RedisClient::GetClient();
    // Use redis here.
}
```

## Lifecycle contract

- Call `Configure()` after loading and validating application configuration.
- Call `GetClient()` only after `Configure()`.
- The first `GetClient()` lazily constructs the shared instance. Later calls return the same `shared_ptr`.
- Calling `Configure()` after the first `GetClient()` attempt throws.
- Do not call either method from ELF/global constructors. C++ static-local initialization is thread-safe, but that does not make application services ready during ELF initialization.

## Important

The constructor currently validates and stores configuration; it does **not** connect to Redis. Add initialization and operations for the Redis client library used by your application. This avoids assuming a particular Redis dependency.
