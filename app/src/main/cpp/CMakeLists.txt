cmake_minimum_required(VERSION 3.22.1)
project(7sven_jni LANGUAGES C CXX)

add_library(7sven_jni SHARED native-lib.cpp)

target_include_directories(7sven_jni PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../llama.cpp/include"
    "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../llama.cpp/ggml/include"
)

target_link_directories(7sven_jni PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/arm64-v8a"
)

target_link_libraries(7sven_jni PRIVATE
    llama ggml ggml-cpu ggml-base log android
)

set_target_properties(7sven_jni PROPERTIES
    CXX_STANDARD 17
    CXX_STANDARD_REQUIRED ON
)
