# SPDX-License-Identifier: GPL-2.0-or-later
cmake_minimum_required(VERSION 3.22.1)
project(noti_doom LANGUAGES C CXX)
set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(DOOM_DIR "${CMAKE_CURRENT_SOURCE_DIR}/vendor/doomgeneric/doomgeneric")
set(OPL_DIR "${CMAKE_CURRENT_SOURCE_DIR}/vendor/chocolate-doom")
set(DOOM_SOURCES
    ${DOOM_DIR}/dummy.c
    ${DOOM_DIR}/am_map.c
    ${DOOM_DIR}/doomdef.c
    ${DOOM_DIR}/doomstat.c
    ${DOOM_DIR}/dstrings.c
    ${DOOM_DIR}/d_event.c
    ${DOOM_DIR}/d_items.c
    ${DOOM_DIR}/d_iwad.c
    ${DOOM_DIR}/d_loop.c
    ${DOOM_DIR}/d_main.c
    ${DOOM_DIR}/d_mode.c
    ${DOOM_DIR}/d_net.c
    ${DOOM_DIR}/f_finale.c
    ${DOOM_DIR}/f_wipe.c
    ${DOOM_DIR}/g_game.c
    ${DOOM_DIR}/hu_lib.c
    ${DOOM_DIR}/hu_stuff.c
    ${DOOM_DIR}/info.c
    ${DOOM_DIR}/i_cdmus.c
    ${DOOM_DIR}/i_endoom.c
    ${DOOM_DIR}/i_joystick.c
    ${DOOM_DIR}/i_scale.c
    ${DOOM_DIR}/i_sound.c
    ${DOOM_DIR}/i_system.c
    ${DOOM_DIR}/i_timer.c
    ${DOOM_DIR}/memio.c
    ${DOOM_DIR}/mus2mid.c
    ${DOOM_DIR}/m_argv.c
    ${DOOM_DIR}/m_bbox.c
    ${DOOM_DIR}/m_cheat.c
    ${DOOM_DIR}/m_config.c
    ${DOOM_DIR}/m_controls.c
    ${DOOM_DIR}/m_fixed.c
    ${DOOM_DIR}/m_menu.c
    ${DOOM_DIR}/m_misc.c
    ${DOOM_DIR}/m_random.c
    ${DOOM_DIR}/p_ceilng.c
    ${DOOM_DIR}/p_doors.c
    ${DOOM_DIR}/p_enemy.c
    ${DOOM_DIR}/p_floor.c
    ${DOOM_DIR}/p_inter.c
    ${DOOM_DIR}/p_lights.c
    ${DOOM_DIR}/p_map.c
    ${DOOM_DIR}/p_maputl.c
    ${DOOM_DIR}/p_mobj.c
    ${DOOM_DIR}/p_plats.c
    ${DOOM_DIR}/p_pspr.c
    ${DOOM_DIR}/p_saveg.c
    ${DOOM_DIR}/p_setup.c
    ${DOOM_DIR}/p_sight.c
    ${DOOM_DIR}/p_spec.c
    ${DOOM_DIR}/p_switch.c
    ${DOOM_DIR}/p_telept.c
    ${DOOM_DIR}/p_tick.c
    ${DOOM_DIR}/p_user.c
    ${DOOM_DIR}/r_bsp.c
    ${DOOM_DIR}/r_data.c
    ${DOOM_DIR}/r_draw.c
    ${DOOM_DIR}/r_main.c
    ${DOOM_DIR}/r_plane.c
    ${DOOM_DIR}/r_segs.c
    ${DOOM_DIR}/r_sky.c
    ${DOOM_DIR}/r_things.c
    ${DOOM_DIR}/sha1.c
    ${DOOM_DIR}/sounds.c
    ${DOOM_DIR}/statdump.c
    ${DOOM_DIR}/st_lib.c
    ${DOOM_DIR}/st_stuff.c
    ${DOOM_DIR}/s_sound.c
    ${DOOM_DIR}/tables.c
    ${DOOM_DIR}/v_video.c
    ${DOOM_DIR}/wi_stuff.c
    ${DOOM_DIR}/w_checksum.c
    ${DOOM_DIR}/w_file.c
    ${DOOM_DIR}/w_main.c
    ${DOOM_DIR}/w_wad.c
    ${DOOM_DIR}/z_zone.c
    ${DOOM_DIR}/w_file_stdc.c
    ${DOOM_DIR}/i_input.c
    ${DOOM_DIR}/i_video.c
    ${DOOM_DIR}/doomgeneric.c
)
# Chocolate Doom 2.2.1's OPL music player and emulator, also unchanged upstream.
# They share the engine archive because each side calls into the other.
set(OPL_SOURCES
    ${OPL_DIR}/src/i_oplmusic.c
    ${OPL_DIR}/src/midifile.c
    ${OPL_DIR}/opl/opl.c
    ${OPL_DIR}/opl/opl_queue.c
    ${OPL_DIR}/opl/dbopl.c
)
add_library(doomgeneric_engine STATIC ${DOOM_SOURCES} ${OPL_SOURCES})
# sdl_compat stands in for the few SDL headers the sound code includes.
target_include_directories(doomgeneric_engine PUBLIC ${DOOM_DIR} ${OPL_DIR}/opl ${OPL_DIR}/src
    ${CMAKE_CURRENT_SOURCE_DIR}/sdl_compat)
target_compile_definitions(doomgeneric_engine PUBLIC DOOMGENERIC_RESX=320 DOOMGENERIC_RESY=200 FEATURE_SOUND)
target_compile_definitions(doomgeneric_engine PRIVATE NORMALUNIX LINUX _DEFAULT_SOURCE exit=noti_doom_exit)
# The upstream files remain byte-for-byte reproducible. Substitute only the
# platform termination/config entry points so Doom cannot exit the Android app.
set_source_files_properties(${DOOM_DIR}/i_system.c PROPERTIES
    COMPILE_DEFINITIONS "I_Error=noti_upstream_error;I_Quit=noti_upstream_quit")
set_source_files_properties(${DOOM_DIR}/m_config.c PROPERTIES
    COMPILE_DEFINITIONS "M_SetConfigDir=noti_upstream_config_dir")
# doom_audio.c supplies the OPL driver (instead of SDL's), wraps the music module,
# and gives the player a temp-file location Android has (instead of /tmp).
set_source_files_properties(${OPL_DIR}/opl/opl.c PROPERTIES
    COMPILE_DEFINITIONS "opl_sdl_driver=noti_opl_driver")
set_source_files_properties(${OPL_DIR}/src/i_oplmusic.c PROPERTIES
    COMPILE_DEFINITIONS "music_opl_module=noti_opl_music_module;M_TempFile=noti_temp_file"
    COMPILE_OPTIONS "-include;${CMAKE_CURRENT_SOURCE_DIR}/doom_opl_compat.h")
set_source_files_properties(${OPL_DIR}/src/midifile.c PROPERTIES
    COMPILE_OPTIONS "-include;${CMAKE_CURRENT_SOURCE_DIR}/sdl_compat/SDL.h")
# Doom intentionally wraps fixed-point integer arithmetic.
target_compile_options(doomgeneric_engine PRIVATE -fwrapv -fno-strict-aliasing)

add_library(noti_doom SHARED doom_runtime.cpp doom_guard.c doom_jni.cpp doom_audio.c)
target_include_directories(noti_doom PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_link_libraries(noti_doom PRIVATE doomgeneric_engine m)
if(ANDROID)
    target_link_libraries(noti_doom PRIVATE log aaudio)
    # Compatible with Android devices using 16 KiB memory pages, including on
    # older NDK versions inherited from the consuming Expo application.
    target_link_options(noti_doom PRIVATE "-Wl,-z,max-page-size=16384")
else()
    find_package(JNI REQUIRED)
    find_package(Threads REQUIRED)
    target_include_directories(noti_doom PRIVATE ${JNI_INCLUDE_DIRS})
    target_link_libraries(noti_doom PRIVATE Threads::Threads)
    option(NOTI_DOOM_BUILD_TESTS "Build native engine integration smoke test" ON)
    if(NOTI_DOOM_BUILD_TESTS)
        enable_testing()
        add_executable(noti_doom_smoke tests/smoke.cpp tests/engine_probe.c)
        target_include_directories(noti_doom_smoke PRIVATE ${CMAKE_CURRENT_SOURCE_DIR} ${DOOM_DIR})
        target_link_libraries(noti_doom_smoke PRIVATE noti_doom Threads::Threads)
        file(MAKE_DIRECTORY ${CMAKE_CURRENT_BINARY_DIR}/test-assets)
        configure_file(../assets/doom/freedoom1.wad test-assets/freedoom1.wad COPYONLY)
        add_test(NAME doom_gameplay COMMAND noti_doom_smoke
            ${CMAKE_CURRENT_BINARY_DIR}/test-assets/freedoom1.wad)
        set_tests_properties(doom_gameplay PROPERTIES TIMEOUT 20)
    endif()
endif()
