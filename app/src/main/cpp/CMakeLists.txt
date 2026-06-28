cmake_minimum_required(VERSION 3.18)
project("fold_audio")

# USB exclusive audio output
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

# DSP engine (EQ + bass/treble)
add_library(dsp_engine SHARED
    dsp-engine.cpp
)

target_link_libraries(dsp_engine
    android
    log
)

target_link_options(dsp_engine PRIVATE
    "-Wl,-z,max-page-size=16384"
)
