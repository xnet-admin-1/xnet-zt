cmake_minimum_required(VERSION 3.10)
project(zt-jni)

set(EXT_DIR ${CMAKE_CURRENT_SOURCE_DIR}/..)
set(ZTO_DIR ${EXT_DIR}/core)
set(LWIP_SRC ${EXT_DIR}/lwip/src)
set(LWIP_PORT ${EXT_DIR}/lwip-contrib/ports/unix/port)

set(ZT_FLAGS "-DZT_USE_MINIUPNPC=1 -DZT_SOFTWARE_UPDATE_DEFAULT=0 -DZT_SDK=1 -DZT_NO_PEER_METRICS=1")
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} ${ZT_FLAGS}")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} ${ZT_FLAGS} -std=c++14")

include_directories(
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${ZTO_DIR}
    ${ZTO_DIR}/node
    ${ZTO_DIR}/osdep
    ${ZTO_DIR}/include
    ${ZTO_DIR}/ext/miniupnpc
    ${ZTO_DIR}/ext/libnatpmp
    ${ZTO_DIR}/ext
    ${EXT_DIR}/concurrentqueue
    # lwIP headers — lwipopts.h is in this directory
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${LWIP_SRC}/include
    ${LWIP_PORT}/include
)

# ZeroTier core (Apache 2.0)
file(GLOB ZTO_SRC ${ZTO_DIR}/node/*.cpp ${ZTO_DIR}/osdep/OSUtils.cpp ${ZTO_DIR}/osdep/PortMapper.cpp ${ZTO_DIR}/osdep/ManagedRoute.cpp ${ZTO_DIR}/osdep/LinuxNetLink.cpp ${ZTO_DIR}/osdep/Http.cpp)

# natpmp (BSD)
set(NATPMP_SRC
    ${ZTO_DIR}/ext/http-parser/http_parser.c
    ${ZTO_DIR}/ext/libnatpmp/natpmp.c
    ${ZTO_DIR}/ext/libnatpmp/wingettimeofday.c
    ${ZTO_DIR}/ext/libnatpmp/getgateway.c)

# miniupnpc (BSD)
set(UPNPC_SRC
    ${ZTO_DIR}/ext/miniupnpc/connecthostport.c
    ${ZTO_DIR}/ext/miniupnpc/igd_desc_parse.c
    ${ZTO_DIR}/ext/miniupnpc/minisoap.c
    ${ZTO_DIR}/ext/miniupnpc/minissdpc.c
    ${ZTO_DIR}/ext/miniupnpc/miniupnpc.c
    ${ZTO_DIR}/ext/miniupnpc/miniwget.c
    ${ZTO_DIR}/ext/miniupnpc/minixml.c
    ${ZTO_DIR}/ext/miniupnpc/portlistingparse.c
    ${ZTO_DIR}/ext/miniupnpc/receivedata.c
    ${ZTO_DIR}/ext/miniupnpc/upnpcommands.c
    ${ZTO_DIR}/ext/miniupnpc/upnpdev.c
    ${ZTO_DIR}/ext/miniupnpc/upnperrors.c
    ${ZTO_DIR}/ext/miniupnpc/upnpreplyparse.c)

# lwIP (BSD)
file(GLOB LWIP_SRC_FILES
    ${LWIP_SRC}/netif/*.c
    ${LWIP_SRC}/api/*.c
    ${LWIP_PORT}/sys_arch.c
    ${LWIP_SRC}/core/*.c
    ${LWIP_SRC}/core/ipv4/*.c
    ${LWIP_SRC}/core/ipv6/*.c)

# libxnet glue (Apache 2.0 — clean-room, replaces GPL libzt src/)
set(XNET_SRC
    ${CMAKE_CURRENT_SOURCE_DIR}/xnet_service.c
    ${CMAKE_CURRENT_SOURCE_DIR}/xnet_tap.c
    ${CMAKE_CURRENT_SOURCE_DIR}/xnet_socket.c
    ${CMAKE_CURRENT_SOURCE_DIR}/jni_bridge.c)

add_library(zt-jni SHARED
    ${XNET_SRC}
    ${ZTO_SRC}
    ${NATPMP_SRC}
    ${UPNPC_SRC}
    ${LWIP_SRC_FILES}
)

target_link_libraries(zt-jni log)
set_target_properties(zt-jni PROPERTIES LINK_FLAGS "-Wl,-z,max-page-size=16384")
