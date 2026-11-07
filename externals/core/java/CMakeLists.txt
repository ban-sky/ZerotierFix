cmake_minimum_required(VERSION 3.5)

project(ZeroTierOneJNI)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(Java COMPONENTS Development)
message("JAVA_HOME: $ENV{JAVA_HOME}")

if(ANDROID AND NOT Java_INCLUDE_DIRS)
    set(Java_INCLUDE_DIRS "$ENV{JAVA_HOME}/include" "$ENV{JAVA_HOME}/include/linux")
endif()

if(WIN32)
set(Java_INCLUDE_DIRS $ENV{JAVA_HOME}/include)
endif()

if(APPLE)
set(Java_INCLUDE_DIRS "/Applications/Xcode.app/Contents/Developer/Platforms/MacOSX.platform/Developer/SDKs/MacOSX10.10.sdk/System/Library/Frameworks/JavaVM.framework/Headers")
endif()

message("Java Include Dirs: ${Java_INCLUDE_DIRS}")

if(WIN32)
    add_definitions(-DNOMINMAX)
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} /EHsc /W3 /MP")
endif()

if(APPLE)
    set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -arch i386 -arch x86_64 -Wall -O3 -flto -fPIE -fvectorize -fstack-protector -mmacosx-version-min=10.7 -Wno-unused-private-field")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} ${CMAKE_C_FLAGS} -fno-rtti")
endif()

# The Android JNI port is built from the complete ZeroTier core source set.
# The hand-maintained list that shipped in java/CMakeLists.txt went stale
# upstream around 1.14: it still referenced the removed ext/lz4 and
# ext/json-parser bundles while missing node/Metrics.cpp, and it did not add
# the prometheus/nlohmann include paths. Globbing node/*.cpp keeps this in
# sync with the core and matches how the official libzt CMake build works.
file(GLOB zt_core_src_files ${CMAKE_CURRENT_SOURCE_DIR}/../node/*.cpp)

set(src_files
    ${zt_core_src_files}
    ../osdep/OSUtils.cpp
    jni/com_zerotierone_sdk_Node.cpp
    jni/ZT_jniutils.cpp
    jni/ZT_jnicache.cpp
    )

set(include_dirs
    ${CMAKE_CURRENT_SOURCE_DIR}/../include/
    ${CMAKE_CURRENT_SOURCE_DIR}/../node/
    ${CMAKE_CURRENT_SOURCE_DIR}/../osdep/
    ${CMAKE_CURRENT_SOURCE_DIR}/../ext/
    ${CMAKE_CURRENT_SOURCE_DIR}/../ext/prometheus-cpp-lite-1.0/core/include/
    ${CMAKE_CURRENT_SOURCE_DIR}/../ext/prometheus-cpp-lite-1.0/simpleapi/include/
    ${Java_INCLUDE_DIRS})

if(WIN32)
    set(include_dirs
        ${include_dirs}
        ${Java_INCLUDE_DIRS}/win32)
endif()

include_directories(
    ${include_dirs}
    )

add_library(${PROJECT_NAME} SHARED ${src_files})

if(APPLE)
    set_target_properties(${PROJECT_NAME} PROPERTIES SUFFIX ".jnilib")
endif()

set(link_libs )

if(ANDROID)
    set(link_libs ${link_libs} log)
endif()

if(WIN32)
    set(link_libs
        wsock32
        ws2_32

        )
endif()

target_link_libraries(${PROJECT_NAME} ${link_libs})
