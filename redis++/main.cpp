// Adapted from the "Getting Started" examples in redis-plus-plus README.
// Demonstrates basic SET/GET and LIST commands against a local Redis server.
#include <sw/redis++/redis++.h>

#include <iostream>
#include <iterator>
#include <string>
#include <vector>

int main() {
    try {
        sw::redis::Redis redis("tcp://127.0.0.1:6379");

        redis.set("example:key", "hello from redis-plus-plus");
        const auto value = redis.get("example:key");
        if (value) {
            std::cout << "example:key = " << *value << '\n';
        } else {
            std::cout << "example:key does not exist\n";
        }

        const std::vector<std::string> items{"one", "two", "three"};
        redis.del("example:list");
        redis.rpush("example:list", items.begin(), items.end());

        std::vector<std::string> result;
        redis.lrange("example:list", 0, -1, std::back_inserter(result));

        std::cout << "example:list:";
        for (const auto &item : result) {
            std::cout << ' ' << item;
        }
        std::cout << '\n';
    } catch (const sw::redis::Error &e) {
        std::cerr << "Redis error: " << e.what() << '\n';
        return 1;
    }

    return 0;
}
