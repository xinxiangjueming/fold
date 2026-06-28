cmake_minimum_required(VERSION 3.18)
project("usb_exclusive_audio")

add_library(usb_audio_output SHARED
    usb_audio_output.cpp
)

target_link_libraries(usb_audio_output
    android
    log
)

target_link_options(usb_audio_output PRIVATE
    "-Wl,-z,max-page-size=16384"
)
