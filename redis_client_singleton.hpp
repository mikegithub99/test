#pragma once

#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>

namespace app {

struct RedisConfig {
    std::string url;
    bool is_cluster = false;
};

// Minimal standalone example of a lazy, process-wide Redis client accessor.
// Replace the constructor body with initialization for your Redis library.
class RedisClient {
public:
    explicit RedisClient(const RedisConfig& config)
        : url_(config.url), is_cluster_(config.is_cluster) {
        if (url_.empty()) {
            throw std::invalid_argument("Redis URL must not be empty");
        }

        // Initialize your actual Redis connection/client here.
        // Avoid connecting in a global/ELF constructor.
    }

    const std::string& url() const noexcept { return url_; }
    bool is_cluster() const noexcept { return is_cluster_; }

    static void Configure(const RedisConfig& config) {
        std::lock_guard<std::mutex> lock(config_mutex_);

        if (client_created_) {
            throw std::logic_error(
                "RedisClient::Configure called after GetClient()");
        }
        if (config.url.empty()) {
            throw std::invalid_argument("Redis URL must not be empty");
        }

        config_ = config;
        configured_ = true;
    }

    static std::shared_ptr<RedisClient> GetClient() {
        // C++11+ guarantees thread-safe initialization of this local static.
        // Configure() must run before the first GetClient() call.
        static const std::shared_ptr<RedisClient> client = [] {
            RedisConfig config;
            {
                std::lock_guard<std::mutex> lock(config_mutex_);
                if (!configured_) {
                    throw std::logic_error(
                        "RedisClient requested before configuration");
                }
                client_created_ = true;
                config = config_;
            }
            return std::make_shared<RedisClient>(config);
        }();

        return client;
    }

private:
    std::string url_;
    bool is_cluster_;

    inline static std::mutex config_mutex_;
    inline static RedisConfig config_{};
    inline static bool configured_ = false;
    inline static bool client_created_ = false;
};

} // namespace app
