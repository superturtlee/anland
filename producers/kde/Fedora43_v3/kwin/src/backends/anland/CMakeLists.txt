target_sources(kwin PRIVATE
    anland_backend.cpp
    anland_egl_backend.cpp
    anland_input.cpp
    anland_logging.cpp
    anland_output.cpp
    # vendored libdisplay_producer (talks to the Android display daemon)
    display_producer.c
    socket_utils.c
)

target_include_directories(kwin PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_link_libraries(kwin PRIVATE Libdrm::Libdrm)
