#include <android/log.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/stat.h>
#include <dlfcn.h>
#include <ctime>

#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include "zygisk.hpp"
#include <jni.h>

#define LOG_TAG "VCamGD"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static constexpr const char *kControlPath = "/data/local/tmp/vcamgd/control.json";
static constexpr const char *kLegacyControlPath = "/data/adb/vcamgd/control.json";
static constexpr const char *kStatusPath = "/data/local/tmp/vcamgd/status.json";
static constexpr const char *kPinePath = "/data/local/tmp/vcamgd/libpine.so";
static constexpr const char *kModulePine = "/data/adb/modules/vcamgd/lib/arm64-v8a/libpine.so";
static constexpr const char *kModuleDex = "/data/adb/modules/vcamgd/dex/hook.dex";

using zygisk::Api;
using zygisk::AppSpecializeArgs;
using zygisk::ServerSpecializeArgs;

static bool read_file(const char *path, std::string &out) {
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return false;
    char buf[4096];
    ssize_t n;
    out.clear();
    while ((n = read(fd, buf, sizeof(buf))) > 0) {
        out.append(buf, static_cast<size_t>(n));
        if (out.size() > 8 * 1024 * 1024) break;
    }
    close(fd);
    return !out.empty() || n == 0;
}

static bool write_file(const char *path, const void *data, size_t len) {
    int fd = open(path, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0644);
    if (fd < 0) return false;
    const char *p = static_cast<const char *>(data);
    size_t left = len;
    while (left > 0) {
        ssize_t n = write(fd, p, left);
        if (n <= 0) {
            close(fd);
            return false;
        }
        p += n;
        left -= static_cast<size_t>(n);
    }
    close(fd);
    return true;
}

static bool json_bool(const std::string &json, const char *key, bool def) {
    std::string needle = std::string("\"") + key + "\"";
    auto pos = json.find(needle);
    if (pos == std::string::npos) return def;
    pos = json.find(':', pos);
    if (pos == std::string::npos) return def;
    auto t = json.find("true", pos);
    auto f = json.find("false", pos);
    if (t != std::string::npos && (f == std::string::npos || t < f)) return true;
    if (f != std::string::npos) return false;
    return def;
}

static bool json_mode_is_real(const std::string &json) {
    auto pos = json.find("\"mode\"");
    if (pos == std::string::npos) return false;
    pos = json.find(':', pos);
    if (pos == std::string::npos) return false;
    auto r = json.find("real", pos);
    auto v = json.find("virtual", pos);
    if (r == std::string::npos) return false;
    if (v != std::string::npos && v < r) return false;
    // Ensure "real" appears soon after mode key
    return r < pos + 24;
}

/** Virtual injection only when enabled and not mode=real. */
static bool control_wants_virtual() {
    std::string json;
    if (!read_file(kControlPath, json)) {
        if (!read_file(kLegacyControlPath, json)) return false;
    }
    if (!json_bool(json, "enabled", false)) return false;
    if (json_mode_is_real(json)) return false;
    if (!json_bool(json, "virtual", true)) return false;
    return true;
}

static void write_status(const char *msg) {
    char buf[512];
    snprintf(buf, sizeof(buf),
             "{\"feeder\":\"%s\",\"ts\":%ld}\n", msg, static_cast<long>(time(nullptr)));
    mkdir("/data/local/tmp/vcamgd", 0777);
    write_file(kStatusPath, buf, strlen(buf));
}

class VCamModule : public zygisk::ModuleBase {
public:
    void onLoad(Api *api, JNIEnv *env) override {
        this->api = api;
        this->env = env;
    }

    void preAppSpecialize(AppSpecializeArgs *args) override {
        const char *process = env->GetStringUTFChars(args->nice_name, nullptr);
        if (process) {
            process_name = process;
            env->ReleaseStringUTFChars(args->nice_name, process);
        }

        const bool isolated = process_name.find(':') != std::string::npos;
        const bool system_server = process_name == "system" || process_name == "system_server";
        const bool webview = process_name.find("webview") != std::string::npos;
        const bool self = process_name == "com.vcamgd.app";
        if (isolated || system_server || webview || self) {
            api->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY);
            should_inject = false;
            return;
        }

        int fd = api->connectCompanion();
        if (fd >= 0) {
            uint32_t size = 0;
            if (read(fd, &size, sizeof(size)) == sizeof(size) && size > 0 && size < 16 * 1024 * 1024) {
                dex_bytes.resize(size);
                size_t got = 0;
                while (got < size) {
                    ssize_t n = read(fd, dex_bytes.data() + got, size - got);
                    if (n <= 0) break;
                    got += static_cast<size_t>(n);
                }
                if (got != size) dex_bytes.clear();
            }
            close(fd);
        }

        // Companion sends 0 bytes when mode=real / disabled — pristine camera path
        should_inject = !dex_bytes.empty();
        if (!should_inject) {
            api->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY);
        }
        LOGI("preAppSpecialize process=%s dex=%zu inject=%d",
             process_name.c_str(), dex_bytes.size(), should_inject ? 1 : 0);
    }

    void postAppSpecialize(const AppSpecializeArgs *args) override {
        (void) args;
        if (!should_inject || dex_bytes.empty()) return;
        if (!ensure_pine_loaded()) {
            write_status("pine_load_failed");
            LOGE("pine load failed");
            return;
        }
        if (!inject_dex_and_install()) {
            write_status("dex_inject_failed");
            LOGE("dex inject failed");
            return;
        }
        write_status("zygisk_hooks_installed");
        LOGI("hooks installed in %s", process_name.c_str());
    }

    void preServerSpecialize(ServerSpecializeArgs *args) override {
        (void) args;
        api->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY);
    }

private:
    Api *api = nullptr;
    JNIEnv *env = nullptr;
    std::string process_name;
    std::vector<uint8_t> dex_bytes;
    bool should_inject = false;

    bool ensure_pine_loaded() {
        if (access(kPinePath, R_OK) != 0) {
            std::string pine;
            if (read_file(kModulePine, pine)) {
                mkdir("/data/local/tmp/vcamgd", 0777);
                write_file(kPinePath, pine.data(), pine.size());
                chmod(kPinePath, 0755);
            }
        }
        jclass sys = env->FindClass("java/lang/System");
        if (sys) {
            jmethodID load = env->GetStaticMethodID(sys, "load", "(Ljava/lang/String;)V");
            if (load) {
                jstring path = env->NewStringUTF(kPinePath);
                env->CallStaticVoidMethod(sys, load, path);
                if (env->ExceptionCheck()) {
                    env->ExceptionClear();
                } else {
                    return true;
                }
            }
        }
        void *h = dlopen(kPinePath, RTLD_NOW);
        if (!h) h = dlopen(kModulePine, RTLD_NOW);
        if (!h) {
            LOGE("dlopen pine: %s", dlerror());
            return false;
        }
        return true;
    }

    bool inject_dex_and_install() {
        JNIEnv *e = env;
        jclass bbufCls = e->FindClass("java/nio/ByteBuffer");
        if (!bbufCls) return false;
        jmethodID wrap = e->GetStaticMethodID(bbufCls, "wrap", "([B)Ljava/nio/ByteBuffer;");
        jbyteArray arr = e->NewByteArray(static_cast<jsize>(dex_bytes.size()));
        e->SetByteArrayRegion(arr, 0, static_cast<jsize>(dex_bytes.size()),
                              reinterpret_cast<const jbyte *>(dex_bytes.data()));
        jobject buffer = e->CallStaticObjectMethod(bbufCls, wrap, arr);

        jclass clCls = e->FindClass("java/lang/ClassLoader");
        jmethodID getSys = e->GetStaticMethodID(clCls, "getSystemClassLoader", "()Ljava/lang/ClassLoader;");
        jobject parent = e->CallStaticObjectMethod(clCls, getSys);

        jclass at = e->FindClass("android/app/ActivityThread");
        if (at) {
            jmethodID curApp = e->GetStaticMethodID(at, "currentApplication", "()Landroid/app/Application;");
            if (curApp) {
                jobject app = e->CallStaticObjectMethod(at, curApp);
                if (app) {
                    jclass ctx = e->FindClass("android/content/ContextWrapper");
                    jmethodID getCl = e->GetMethodID(ctx, "getClassLoader", "()Ljava/lang/ClassLoader;");
                    if (getCl) {
                        jobject appCl = e->CallObjectMethod(app, getCl);
                        if (appCl) parent = appCl;
                    }
                }
            }
        }

        jclass imdcl = e->FindClass("dalvik/system/InMemoryDexClassLoader");
        if (!imdcl) return false;
        jmethodID ctor = e->GetMethodID(imdcl, "<init>", "(Ljava/nio/ByteBuffer;Ljava/lang/ClassLoader;)V");
        jobject loader = e->NewObject(imdcl, ctor, buffer, parent);
        if (!loader || e->ExceptionCheck()) {
            e->ExceptionClear();
            return false;
        }

        jmethodID loadClass = e->GetMethodID(clCls, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
        jstring name = e->NewStringUTF("com.vcamgd.hook.HookEntry");
        jobject hookClass = e->CallObjectMethod(loader, loadClass, name);
        if (!hookClass || e->ExceptionCheck()) {
            e->ExceptionDescribe();
            e->ExceptionClear();
            return false;
        }

        jmethodID installWithName = e->GetStaticMethodID(
                static_cast<jclass>(hookClass), "install", "(Ljava/lang/String;)V");
        if (installWithName) {
            jstring pname = e->NewStringUTF(process_name.c_str());
            e->CallStaticVoidMethod(static_cast<jclass>(hookClass), installWithName, pname);
        } else {
            jmethodID install = e->GetStaticMethodID(static_cast<jclass>(hookClass), "install", "()V");
            if (!install) return false;
            e->CallStaticVoidMethod(static_cast<jclass>(hookClass), install);
        }
        if (e->ExceptionCheck()) {
            e->ExceptionDescribe();
            e->ExceptionClear();
            return false;
        }
        return true;
    }
};

static void companion_handler(int client) {
    mkdir("/data/local/tmp/vcamgd", 0777);
    mkdir("/data/adb/vcamgd", 0755);

    std::string pine;
    if (read_file(kModulePine, pine)) {
        write_file(kPinePath, pine.data(), pine.size());
        chmod(kPinePath, 0755);
    }

    // CRITICAL: no dex => no Pine hooks => stock camera opens normally
    if (!control_wants_virtual()) {
        LOGI("companion skip: virtual mode off");
        write_status("passthrough_no_inject");
        uint32_t zero = 0;
        write(client, &zero, sizeof(zero));
        return;
    }

    std::string dex;
    if (!read_file(kModuleDex, dex)) {
        LOGE("companion missing hook.dex");
        uint32_t zero = 0;
        write(client, &zero, sizeof(zero));
        return;
    }
    uint32_t size = static_cast<uint32_t>(dex.size());
    write(client, &size, sizeof(size));
    write(client, dex.data(), size);
    write_status("companion_sent_dex");
    LOGI("companion sent dex bytes=%u", size);
}

REGISTER_ZYGISK_MODULE(VCamModule)
REGISTER_ZYGISK_COMPANION(companion_handler)
