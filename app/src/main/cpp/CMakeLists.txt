cmake_minimum_required(VERSION 3.18)
project("usb_exclusive_audio")

set(LIBUSB_DIR ${CMAKE_CURRENT_SOURCE_DIR}/libusb/libusb)

# ── libusb config.h (Android/Linux 完整配置) ──
file(WRITE ${CMAKE_CURRENT_BINARY_DIR}/config.h
"/* Auto-generated for Android NDK */
#define OS_LINUX 1
#define PLATFORM_POSIX 1
#define THREADS_POSIX 1
#define EVENT_SOURCE_EVENTFD 1
#define HAVE_CLOCK_GETTIME 1
#define HAVE_EVENTFD 1
#define HAVE_TIMERFD 1
#define HAVE_PIPE2 1
#define HAVE_NFDS_T 1
#define HAVE_STRUCT_TIMESPEC 1
#define HAVE_SYS_TIME_H 1
#define HAVE_TIMERFD 1
#define _GNU_SOURCE 1
#define HAVE_ASM_TYPES_H 1
#define HAVE_LINUX_NETLINK_H 1
#define HAVE_SIGNAL_H 1
#define HAVE_POLL_H 1
#define HAVE_SYSLOG_H 1
#define DEFAULT_VISIBILITY __attribute__((visibility(\"default\")))
")

# ── libusb 静态库 ──
add_library(usb-1.0 STATIC
    ${LIBUSB_DIR}/core.c
    ${LIBUSB_DIR}/descriptor.c
    ${LIBUSB_DIR}/hotplug.c
    ${LIBUSB_DIR}/io.c
    ${LIBUSB_DIR}/strerror.c
    ${LIBUSB_DIR}/sync.c
    ${LIBUSB_DIR}/os/linux_usbfs.c
    ${LIBUSB_DIR}/os/linux_netlink.c
    ${LIBUSB_DIR}/os/events_posix.c
    ${LIBUSB_DIR}/os/threads_posix.c
)

target_include_directories(usb-1.0 PUBLIC
    ${LIBUSB_DIR}
    ${LIBUSB_DIR}/os
    ${CMAKE_CURRENT_BINARY_DIR}
)

target_compile_definitions(usb-1.0 PRIVATE
    _GNU_SOURCE
    OS_LINUX
    PLATFORM_POSIX
    THREADS_POSIX
    HAVE_CLOCK_GETTIME
    HAVE_EVENTFD
    HAVE_TIMERFD
    HAVE_PIPE2
    HAVE_NFDS_T
    HAVE_SYS_TIME_H
    HAVE_ASM_TYPES_H
    HAVE_LINUX_NETLINK_H
    HAVE_SIGNAL_H
    HAVE_POLL_H
    HAVE_SYSLOG_H
)

target_compile_options(usb-1.0 PRIVATE -Wno-unused-parameter -Wno-sign-compare)

# ── JNI native 库 ──
add_library(usb_audio_native SHARED
    usb_audio_native.c
)

target_include_directories(usb_audio_native PRIVATE
    ${LIBUSB_DIR}
    ${LIBUSB_DIR}/os
    ${CMAKE_CURRENT_BINARY_DIR}
)

target_link_libraries(usb_audio_native
    usb-1.0
    android
    log
)

# 16KB 页面对齐（Android 15+ 要求）
target_link_options(usb_audio_native PRIVATE
    "-Wl,-z,max-page-size=16384"
)
