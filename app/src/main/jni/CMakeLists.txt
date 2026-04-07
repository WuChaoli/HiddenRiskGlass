cmake_minimum_required(VERSION 3.10)
project(hiddenriskncnn)

set(OpenCV_DIR ${CMAKE_SOURCE_DIR}/opencv-mobile-4.13.0-android/sdk/native/jni)
find_package(OpenCV REQUIRED core imgproc)

set(NCNN_PREBUILT_ROOT ${CMAKE_SOURCE_DIR}/ncnn-20260113-android-vulkan/${ANDROID_ABI})

add_library(GenericCodeGen STATIC IMPORTED GLOBAL)
set_target_properties(GenericCodeGen PROPERTIES
    IMPORTED_LOCATION ${NCNN_PREBUILT_ROOT}/lib/libGenericCodeGen.a
)

add_library(MachineIndependent STATIC IMPORTED GLOBAL)
set_target_properties(MachineIndependent PROPERTIES
    IMPORTED_LOCATION ${NCNN_PREBUILT_ROOT}/lib/libMachineIndependent.a
)

add_library(OSDependent STATIC IMPORTED GLOBAL)
set_target_properties(OSDependent PROPERTIES
    IMPORTED_LOCATION ${NCNN_PREBUILT_ROOT}/lib/libOSDependent.a
)

add_library(glslang-default-resource-limits STATIC IMPORTED GLOBAL)
set_target_properties(glslang-default-resource-limits PROPERTIES
    IMPORTED_LOCATION ${NCNN_PREBUILT_ROOT}/lib/libglslang-default-resource-limits.a
)

add_library(SPIRV STATIC IMPORTED GLOBAL)
set_target_properties(SPIRV PROPERTIES
    IMPORTED_LOCATION ${NCNN_PREBUILT_ROOT}/lib/libSPIRV.a
    INTERFACE_INCLUDE_DIRECTORIES ${NCNN_PREBUILT_ROOT}/include
)

add_library(glslang STATIC IMPORTED GLOBAL)
set_target_properties(glslang PROPERTIES
    IMPORTED_LOCATION ${NCNN_PREBUILT_ROOT}/lib/libglslang.a
    INTERFACE_INCLUDE_DIRECTORIES ${NCNN_PREBUILT_ROOT}/include
    INTERFACE_LINK_LIBRARIES "SPIRV;glslang-default-resource-limits;GenericCodeGen;MachineIndependent;OSDependent"
)

add_library(ncnn STATIC IMPORTED GLOBAL)
set_target_properties(ncnn PROPERTIES
    IMPORTED_LOCATION ${NCNN_PREBUILT_ROOT}/lib/libncnn.a
    INTERFACE_INCLUDE_DIRECTORIES ${NCNN_PREBUILT_ROOT}/include/ncnn
    INTERFACE_LINK_LIBRARIES "glslang"
)

add_library(hiddenriskncnn SHARED yolov8ncnn.cpp yolov8.cpp yolov8_det.cpp)

target_link_libraries(hiddenriskncnn ncnn ${OpenCV_LIBS} android jnigraphics log)
