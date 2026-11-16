cmake_minimum_required(VERSION 3.18.1)
project("fimage")

add_definitions(
        -D_LIBCPP_HAS_NO_OFF_T_FUNCTIONS
        -DPNG_ARM_NEON_OPT=0
        -Dswab=tiff_swab
)
add_subdirectory(
        freeimage
)
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384 -Wl,--allow-multiple-definition")
set(CMAKE_EXE_LINKER_FLAGS "${CMAKE_EXE_LINKER_FLAGS} -Wl,-z,max-page-size=16384 -Wl,--allow-multiple-definition")
add_library(
        fimage
        SHARED
        fimage.cpp)

#add_executable(
#      test
#      test.cpp
#)

find_library(
        log-lib
        log)

include_directories(
        freeimage/Source
)
target_link_libraries(
        fimage
        ${log-lib}
        FreeImage
)
#target_link_libraries(
#      test
#      FreeImage
#)