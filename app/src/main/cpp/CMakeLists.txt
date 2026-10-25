cmake_minimum_required(VERSION 3.22.1)
project("motocam_native")

find_package(OpenMP QUIET)

add_library(
    motocam_native
    SHARED
    native_processor.cpp
)

find_library(log-lib log)
find_library(jnigraphics-lib jnigraphics)

if(OpenMP_CXX_FOUND)
    target_link_libraries(
        motocam_native
        ${log-lib}
        ${jnigraphics-lib}
        OpenMP::OpenMP_CXX
    )
else()
    target_link_libraries(
        motocam_native
        ${log-lib}
        ${jnigraphics-lib}
    )
endif()
