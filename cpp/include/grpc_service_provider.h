#pragma once
#include <grpcpp/grpcpp.h>
#include <atomic>
#include <memory>
#include <string>
#include <thread>
#include "registry.grpc.pb.h"

namespace grpcdiscovery {
class ServiceProvider {
public:
    ServiceProvider(std::shared_ptr<grpc::Channel> registry_channel,
                    std::string service_name, std::string instance_id,
                    std::string host, int port, int ttl_seconds = 30);
    ~ServiceProvider();
    ServiceProvider(const ServiceProvider&) = delete;
    ServiceProvider& operator=(const ServiceProvider&) = delete;
    void start();
    void stop();
private:
    void heartbeatLoop();
    bool registerService();
    void unregisterService();
    bool heartbeat();
    std::shared_ptr<grpc::Channel> registry_channel_;
    std::unique_ptr<registry::GrpcServiceRegistry::Stub> stub_;
    const std::string service_name_, instance_id_, host_;
    const int port_, ttl_seconds_;
    std::atomic<bool> running_{false};
    std::thread heartbeat_thread_;
};
}
