cmake_minimum_required(VERSION 3.22.1)
project(luacore C)

# ---- Lua 5.4 core, embedded as a static lib (no lua.c/luac.c CLI files) ----
file(GLOB LUA_SOURCES ${CMAKE_SOURCE_DIR}/lua/*.c)

add_library(lua54 STATIC ${LUA_SOURCES})
target_include_directories(lua54 PUBLIC ${CMAKE_SOURCE_DIR}/lua)
target_compile_definitions(lua54 PUBLIC
    LUA_USE_LINUX          # gives us dlopen-based require() + readline-free line editing
    LUA_USE_POSIX
)
# Android's bionic libc lacks a couple of glibc-only symbols LUA_USE_LINUX assumes.
target_compile_definitions(lua54 PUBLIC LUA_USE_DLOPEN)

# ---- JNI bridge: Kotlin <-> Lua ----
add_library(luabridge SHARED lua_bridge.c)
target_link_libraries(luabridge lua54 log android m dl)

# Position-independent + stripped-of-nothing-we-need; PIE/relro are handled by AGP by default.
set_target_properties(luabridge PROPERTIES
    C_STANDARD 11
    C_STANDARD_REQUIRED ON
)
