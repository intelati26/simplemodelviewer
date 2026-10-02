cmake_minimum_required(VERSION 3.18)
project(spcrefs_core LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

add_library(zstd_decoder STATIC third_party/zstd/zstddeclib.c)

# zlib: gunzip of the MRMS files and the inflate of the PNG packed inside them (system zlib on the host, the platform's on Android / iOS)
if(ANDROID OR CMAKE_SYSTEM_NAME STREQUAL "iOS")
    set(CORE_ZLIB z)
else()
    find_package(ZLIB REQUIRED)
    set(CORE_ZLIB ZLIB::ZLIB)
endif()

add_library(spcrefs_core STATIC
    src/blosc_decode.cpp src/chunk_decode.cpp src/raster.cpp src/grids.cpp src/mrms_decode.cpp src/c_api.cpp)
target_include_directories(spcrefs_core PUBLIC include PRIVATE src)
target_link_libraries(spcrefs_core PRIVATE zstd_decoder ${CORE_ZLIB})

# Android: the JNI bridge the app loads as libsimplemodelview_jni.so
if(ANDROID)
    add_library(simplemodelview_jni SHARED jni/simplemodelview_jni.cpp)
    target_link_libraries(simplemodelview_jni PRIVATE spcrefs_core)
endif()

# unit tests (host builds only, not the Android / iOS cross builds)
if(NOT ANDROID AND NOT CMAKE_SYSTEM_NAME STREQUAL "iOS")
    enable_testing()
    add_executable(core_tests tests/test_core.cpp)
    target_include_directories(core_tests PRIVATE src)
    target_compile_definitions(core_tests PRIVATE FIXTURE_DIR="${CMAKE_CURRENT_SOURCE_DIR}/../fixtures/mrms")
    target_link_libraries(core_tests PRIVATE spcrefs_core)
    # optional: encode with the reference libblosc and check the decoder is byte-identical
    find_path(BLOSC_INCLUDE blosc.h)
    find_library(BLOSC_LIB blosc)
    if(BLOSC_INCLUDE AND BLOSC_LIB)
        target_compile_definitions(core_tests PRIVATE HAVE_LIBBLOSC=1)
        target_include_directories(core_tests PRIVATE ${BLOSC_INCLUDE})
        target_link_libraries(core_tests PRIVATE ${BLOSC_LIB})
    endif()
    add_test(NAME core_tests COMMAND core_tests)
endif()
