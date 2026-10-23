cmake_minimum_required(VERSION 3.22.1)
project(pkgextract LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(pkgextract SHARED jni_bridge.cpp extractor_adapter.cpp)
target_link_libraries(pkgextract log z)

# shadPS4Plus sources are copied here by setup.sh (src/ and externals/).
set(SP ${CMAKE_CURRENT_SOURCE_DIR}/shadps4)

if(EXISTS ${SP}/src/core/file_format/pkg.cpp)
    message(STATUS "shadPS4Plus sources found: building real extractor")

    # Same file list as extractor/CMakeLists.txt
    target_sources(pkgextract PRIVATE
        ${SP}/src/core/loader.cpp
        ${SP}/src/core/file_format/pkg.cpp
        ${SP}/src/core/file_format/psf.cpp
        ${SP}/src/core/file_format/pkg_type.cpp
        ${SP}/src/core/crypto/crypto.cpp
        ${SP}/src/common/io_file.cpp
        ${SP}/src/common/assert.cpp
        ${SP}/src/common/error.cpp
        ${SP}/src/common/path_util.cpp
        ${SP}/src/common/string_util.cpp)

    target_include_directories(pkgextract PRIVATE ${SP}/src ${SP}/externals)
    if(EXISTS ${SP}/externals/fmt/include)
        target_include_directories(pkgextract PRIVATE ${SP}/externals/fmt/include)
        target_compile_definitions(pkgextract PRIVATE FMT_HEADER_ONLY=1)
    endif()
    target_compile_definitions(pkgextract PRIVATE HAVE_EXTRACTOR=1)

    # Crypto++ built from source (original uses a Makefile build; ASM off for portability)
    file(GLOB CRYPTOPP_SRC ${SP}/externals/cryptopp/*.cpp)
    list(FILTER CRYPTOPP_SRC EXCLUDE REGEX ".*/(bench[0-9]*|test|validat[0-9]*|datatest|regtest[0-9]*|fipsalgt|dlltest|cryptlib_bds|fipstest|adhoc|ppc_power[0-9]*|ppc_simd|.*_armv4|neon_simd)\\.cpp$")
    add_library(cryptopp STATIC ${CRYPTOPP_SRC})
    target_include_directories(cryptopp PRIVATE ${SP}/externals/cryptopp)
    target_compile_definitions(cryptopp PUBLIC CRYPTOPP_DISABLE_ASM=1 CRYPTOPP_NO_CXX17_UNCAUGHT_EXCEPTIONS=1)
    set_target_properties(cryptopp PROPERTIES POSITION_INDEPENDENT_CODE ON)
    target_include_directories(cryptopp PUBLIC ${ANDROID_NDK}/sources/android/cpufeatures)
    target_sources(cryptopp PRIVATE ${ANDROID_NDK}/sources/android/cpufeatures/cpu-features.c)
    target_link_libraries(pkgextract cryptopp z log)
else()
    message(WARNING "shadps4/ sources missing - run setup.sh. Building stub only.")
endif()
