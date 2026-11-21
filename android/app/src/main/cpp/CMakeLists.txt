cmake_minimum_required(VERSION 3.22.1)
project(mmc_runtime_injector)

set(CMAKE_CXX_STANDARD 17)

find_library(log-lib log)

# Core ptrace/dlopen engine, shared by the JNI wrapper and the privileged CLI.
add_library(injector_core STATIC injector_core.cpp)
target_link_libraries(injector_core ${log-lib})

# JNI entry point (used when the host app itself is root, or for self-tests).
add_library(injector SHARED injector.cpp)
target_link_libraries(injector injector_core ${log-lib})

# Privileged CLI, executed through `su -c`. Copied into assets after the build
# so AGP packages it; Kotlin stages it into filesDir before running it.
add_executable(injector_cli injector_cli.cpp)
target_link_libraries(injector_cli injector_core ${log-lib})
set_target_properties(injector_cli PROPERTIES
        RUNTIME_OUTPUT_DIRECTORY "${CMAKE_CURRENT_SOURCE_DIR}/../assets")

# Phase-1 PoC payload: a trivial .so whose constructor writes a marker file.
# dlopen()-ing this first proves remote loading works before the full LGL mod.
add_library(marker SHARED marker.c)
