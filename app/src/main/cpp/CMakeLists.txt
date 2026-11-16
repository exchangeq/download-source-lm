cmake_minimum_required(VERSION 3.22)
project(zerocore LANGUAGES CXX)

add_library(zerocore SHARED zerocore.cpp)
target_compile_features(zerocore PRIVATE cxx_std_17)
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -flto")

find_library(log-lib log)
find_library(z-lib z)

target_link_libraries(zerocore ${log-lib} ${z-lib})
