cmake_minimum_required(VERSION 3.5)

project(zbcode)

## 子项目
add_subdirectory(backend)

include_directories(
        backend
)

set(
        SRCS
        main.cpp
        com_gainscha_jzint_JzInt.cpp
)
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384")
set(CMAKE_EXE_LINKER_FLAGS "${CMAKE_EXE_LINKER_FLAGS} -Wl,-z,max-page-size=16384")


add_library(
        ${PROJECT_NAME}
        SHARED
        ${SRCS}
)
find_library(
        log-lib
        # 查找log依赖库
        # {sdk-path}/ndk-bundle/sysroot/usr/include/android/log.h
        log)

target_link_libraries(
        ${PROJECT_NAME}
        zint-static
        ${log-lib}
)
