cmake_minimum_required(VERSION 3.22)
project(mekomod CXX)

add_library(mekomod SHARED payload.cpp)
target_compile_features(mekomod PRIVATE cxx_std_17)
target_compile_options(mekomod PRIVATE -Wall -Wextra -fvisibility=hidden)
# STL statis: APK game asli tidak membawa libc++_shared.so, jadi payload
# tidak boleh bergantung padanya.
target_link_options(mekomod PRIVATE -static-libstdc++)
target_link_libraries(mekomod log dl)
