# RedisClient

## Purpose

`RedisClient` provides process-wide access to either a standalone Redis client or a Redis Cluster client, selected from application configuration.

## Why a separate lazy singleton?

The existing application singleton mechanism is not used because the configuration values required to construct the Redis client are not available when that singleton is created.

`RedisClient` therefore uses lazy initialization through a function-local static instance in `GetClient()`. The Redis client is constructed on the first call to `GetClient()`, so the call must happen only after application configuration has been initialized.

## Configuration

The constructor reads these configuration values:

- `redisUrl`: the Redis connection URL.
- `isRedisCluster`: whether to create a Redis Cluster client.

An empty `redisUrl` causes construction to throw `std::invalid_argument`. Exceptions from configuration access or client construction may also propagate.

## Usage

```cpp
auto client = RedisClient::GetClient();

client->execute([](auto& redis) {
  // Use the active Redis or Redis Cluster client here.
});
```

The callable passed to `execute()` receives a reference to the active client. Its return value is forwarded to the caller.

## Requirements and notes

- Requires C++17 and redis-plus-plus.
- The project must make the `get_reg_val` configuration accessor available to `RedisClient.cc`.
- Call `GetClient()` only after configuration has been initialized.
- Function-local static initialization is thread-safe in C++11 and later. This does not, by itself, establish whether concurrent operations on the underlying Redis client are safe; consult the redis-plus-plus guarantees for the version in use.
