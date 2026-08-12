APP_ABI := arm64-v8a armeabi-v7a
APP_PLATFORM := android-31
APP_STL := c++_static
APP_CFLAGS := -O2 -fPIE -D_FILE_OFFSET_BITS=64
APP_LDFLAGS := -pie -Wl,-z,max-page-size=16384
