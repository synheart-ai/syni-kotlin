# CMakeLists.txt for syni-runtime JNI bindings
cmake_minimum_required(VERSION 3.18.1)

project("syni_jni")

# Find the syni_ffi library
# In production, this should be a prebuilt .so in jniLibs
# For development, we link against the local build
set(SYNI_FFI_DIR "${CMAKE_SOURCE_DIR}/../../../../../../syni-runtime/target")

# Create the JNI bridge library
add_library(syni_jni SHARED
    syni_jni.c
)

# Find the appropriate syni_ffi library based on architecture
if(CMAKE_BUILD_TYPE STREQUAL "Debug")
    set(SYNI_FFI_BUILD_DIR "${SYNI_FFI_DIR}/debug")
else()
    set(SYNI_FFI_BUILD_DIR "${SYNI_FFI_DIR}/release")
endif()

# For Android, we need the cross-compiled library
# The actual path depends on the target ABI
set(SYNI_FFI_LIB_DIR "${SYNI_FFI_BUILD_DIR}")

# Link against syni_ffi
# Option 1: Use prebuilt library from jniLibs (preferred for production)
# Option 2: Link against locally built library (for development)

# Check if we have prebuilt libraries in jniLibs
set(JNILIBS_DIR "${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}")
if(EXISTS "${JNILIBS_DIR}/libsyni_ffi.so")
    # Use prebuilt library
    add_library(syni_ffi SHARED IMPORTED)
    set_target_properties(syni_ffi PROPERTIES
        IMPORTED_LOCATION "${JNILIBS_DIR}/libsyni_ffi.so"
    )
    target_link_libraries(syni_jni syni_ffi)
    message(STATUS "Using prebuilt syni_ffi from jniLibs/${ANDROID_ABI}")
else()
    # For development, just declare the symbols (library must be loaded separately)
    message(WARNING "No prebuilt syni_ffi found in jniLibs/${ANDROID_ABI}. Build will proceed but runtime linking required.")
endif()

# Link Android log library
find_library(log-lib log)
target_link_libraries(syni_jni ${log-lib})

# Include JNI headers
target_include_directories(syni_jni PRIVATE
    ${CMAKE_SOURCE_DIR}
)
