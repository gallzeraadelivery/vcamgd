LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := kingvd
LOCAL_SRC_FILES := kingvd.cpp
LOCAL_LDLIBS := -llog
LOCAL_CFLAGS := -Wall -O2
LOCAL_LDFLAGS := -Wl,-z,max-page-size=16384
include $(BUILD_EXECUTABLE)
