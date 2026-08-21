cmake_minimum_required(VERSION 3.24)

project(omnicode_appcontainer_host LANGUAGES CXX)

add_executable(omnicode-appcontainer-host omnicode-appcontainer-host.cpp)
target_compile_features(omnicode-appcontainer-host PRIVATE cxx_std_17)
target_compile_definitions(omnicode-appcontainer-host PRIVATE UNICODE _UNICODE NOMINMAX WIN32_LEAN_AND_MEAN _WIN32_WINNT=0x0A00)
target_link_libraries(omnicode-appcontainer-host PRIVATE advapi32 userenv shell32)

if (MSVC)
    # Keep warnings visible in the release build, but do not make a newly introduced
    # SDK warning turn the Marketplace pipeline into a false negative. The helper is
    # still protected by the linker hardening flags below and the Windows job runs the
    # API probe plus the workspace ACL smoke test before packaging it.
    target_compile_options(omnicode-appcontainer-host PRIVATE /W4 /guard:cf /sdl)
    target_link_options(omnicode-appcontainer-host PRIVATE /DYNAMICBASE /NXCOMPAT /CETCOMPAT)
endif()

set_target_properties(omnicode-appcontainer-host PROPERTIES
    OUTPUT_NAME "omnicode-appcontainer-host"
    RUNTIME_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/bin"
    RUNTIME_OUTPUT_DIRECTORY_RELEASE "${CMAKE_BINARY_DIR}/bin"
)
