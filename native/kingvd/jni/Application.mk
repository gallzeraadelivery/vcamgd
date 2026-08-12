APP_ABI := arm64-v8a armeabi-v7a
APP_PLATFORM := android-31
APP_STL := c++_static
APP_CPPFLAGS := -std=c++17 -fno-exceptions -fno-rtti
# Android 15+ 16KB page size
APP_LDFLAGS := -Wl,-z,max-page-size=16384
