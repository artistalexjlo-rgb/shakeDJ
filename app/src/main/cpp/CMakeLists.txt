cmake_minimum_required(VERSION 3.22.1)
project(shakedj_audio C)

add_library(shakedj_audio SHARED shakedj_audio.c)
target_link_libraries(shakedj_audio dl)
# 16 KB pages (Android 15+ devices).
target_link_options(shakedj_audio PRIVATE "-Wl,-z,max-page-size=16384")
