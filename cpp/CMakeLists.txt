cmake_minimum_required(VERSION 3.20)
project(grpc_service_provider_cpp LANGUAGES CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
find_package(Protobuf CONFIG REQUIRED)
find_package(gRPC CONFIG REQUIRED)
set(PROTO_FILE ${CMAKE_CURRENT_SOURCE_DIR}/../src/main/proto/registry.proto)
get_filename_component(PROTO_DIR ${PROTO_FILE} DIRECTORY)
set(GEN_DIR ${CMAKE_CURRENT_BINARY_DIR}/generated)
file(MAKE_DIRECTORY ${GEN_DIR})
add_custom_command(
 OUTPUT ${GEN_DIR}/registry.pb.cc ${GEN_DIR}/registry.pb.h ${GEN_DIR}/registry.grpc.pb.cc ${GEN_DIR}/registry.grpc.pb.h
 COMMAND protobuf::protoc
 ARGS --proto_path=${PROTO_DIR} --cpp_out=${GEN_DIR} --grpc_out=${GEN_DIR}
      --plugin=protoc-gen-grpc=$<TARGET_FILE:gRPC::grpc_cpp_plugin> ${PROTO_FILE}
 DEPENDS ${PROTO_FILE})
add_library(registry_proto ${GEN_DIR}/registry.pb.cc ${GEN_DIR}/registry.grpc.pb.cc)
target_include_directories(registry_proto PUBLIC ${GEN_DIR})
target_link_libraries(registry_proto PUBLIC protobuf::libprotobuf gRPC::grpc++)
add_library(grpc_service_provider src/grpc_service_provider.cpp)
target_include_directories(grpc_service_provider PUBLIC include)
target_link_libraries(grpc_service_provider PUBLIC registry_proto gRPC::grpc++)
