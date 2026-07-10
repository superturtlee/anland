target_sources(kwin PRIVATE
    anland_backend.cpp
    anland_egl_backend.cpp
    anland_input.cpp
    anland_logging.cpp
    anland_output.cpp
    # PipeWire audio engine: sink-monitor capture + virtual mic source
    anland_audio.c
    # PipeWire camera engine: virtual Video/Source nodes fed by the Android camera
    anland_camera.c
    # vendored libdisplay_producer (talks to the Android display daemon)
    display_producer.c
    socket_utils.c
)

target_include_directories(kwin PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
# PkgConfig::PipeWire is already resolved at the top level for the screencast plugin.
target_link_libraries(kwin PRIVATE Libdrm::Libdrm PkgConfig::PipeWire)
