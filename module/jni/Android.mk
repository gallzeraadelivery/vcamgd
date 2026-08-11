LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := vcamgd
LOCAL_SRC_FILES := module.cpp
LOCAL_C_INCLUDES := $(LOCAL_PATH)
LOCAL_LDLIBS := -llog
LOCAL_CFLAGS := -std=c++17 -fno-exceptions -fno-rtti
include $(BUILD_SHARED_LIBRARY)
