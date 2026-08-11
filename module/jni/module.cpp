#include <android/log.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/stat.h>
#include <ctime>

#include <cstdio>
#include <cstring>
#include <string>

#include "zygisk.hpp"

#define LOG_TAG "VCamGD"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static constexpr const char *kControlPath = "/data/adb/vcamgd/control.json";
static constexpr const char *kStatusPath = "/data/adb/vcamgd/status.json";

struct ControlState {
    bool enabled = false;
    bool virtual_cam = true;
    char source[16]{};
    char uri[512]{};
    char url[512]{};
};

static bool read_file(const char *path, std::string &out) {
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return false;
    char buf[2048];
    ssize_t n;
    out.clear();
    while ((n = read(fd, buf, sizeof(buf))) > 0) {
        out.append(buf, static_cast<size_t>(n));
        if (out.size() > 8192) break;
    }
    close(fd);
    return true;
}

static bool write_file(const char *path, const std::string &data) {
    int fd = open(path, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0644);
    if (fd < 0) return false;
    const char *p = data.data();
    size_t left = data.size();
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

static void json_string(const std::string &json, const char *key, char *out, size_t out_sz) {
    out[0] = 0;
    std::string needle = std::string("\"") + key + "\"";
    auto pos = json.find(needle);
    if (pos == std::string::npos) return;
    pos = json.find(':', pos);
    if (pos == std::string::npos) return;
    pos = json.find('"', pos);
    if (pos == std::string::npos) return;
    auto end = json.find('"', pos + 1);
    if (end == std::string::npos) return;
    size_t len = end - (pos + 1);
    if (len >= out_sz) len = out_sz - 1;
    memcpy(out, json.c_str() + pos + 1, len);
    out[len] = 0;
}

static ControlState parse_control(const std::string &json) {
    ControlState s;
    s.enabled = json_bool(json, "enabled", false);
    s.virtual_cam = json_bool(json, "virtual", true);
    json_string(json, "source", s.source, sizeof(s.source));
    json_string(json, "uri", s.uri, sizeof(s.uri));
    json_string(json, "url", s.url, sizeof(s.url));
    return s;
}

static void append_status_event(const char *process, const ControlState &state) {
    char line[1024];
    snprintf(
        line,
        sizeof(line),
        "{\"process\":\"%s\",\"enabled\":%s,\"virtual\":%s,\"source\":\"%s\",\"ts\":%ld}\n",
        process,
        state.enabled ? "true" : "false",
        state.virtual_cam ? "true" : "false",
        state.source,
        static_cast<long>(time(nullptr))
    );
    // overwrite small status file with latest event
    write_file(kStatusPath, line);
}

using zygisk::Api;
using zygisk::AppSpecializeArgs;
using zygisk::ServerSpecializeArgs;

class VCamModule : public zygisk::ModuleBase {
public:
    void onLoad(Api *api, JNIEnv *env) override {
        this->api = api;
        this->env = env;
    }

    void preAppSpecialize(AppSpecializeArgs *args) override {
        const char *process = env->GetStringUTFChars(args->nice_name, nullptr);
        if (!process) {
            api->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY);
            return;
        }
        process_name = process;
        env->ReleaseStringUTFChars(args->nice_name, process);

        int fd = api->connectCompanion();
        if (fd >= 0) {
            uint8_t flag = 0;
            if (read(fd, &flag, 1) == 1) {
                control_enabled = flag == 1;
            }
            // optional: read rest of control blob size + payload
            uint32_t size = 0;
            if (read(fd, &size, sizeof(size)) == sizeof(size) && size > 0 && size < 8192) {
                std::string json(size, '\0');
                size_t got = 0;
                while (got < size) {
                    ssize_t n = read(fd, json.data() + got, size - got);
                    if (n <= 0) break;
                    got += static_cast<size_t>(n);
                }
                if (got == size) {
                    control = parse_control(json);
                    control_enabled = control.enabled;
                }
            }
            close(fd);
        }

        // Keep loaded when virtual camera is enabled, or in our own app for diagnostics.
        bool keep = control_enabled || process_name == "com.vcamgd.app";
        if (!keep) {
            api->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY);
        }
        LOGI("preAppSpecialize process=%s enabled=%d", process_name.c_str(), control_enabled ? 1 : 0);
    }

    void postAppSpecialize(const AppSpecializeArgs *args) override {
        (void) args;
        if (!control_enabled && process_name != "com.vcamgd.app") {
            return;
        }
        append_status_event(process_name.c_str(), control);
        // Hook point for future Camera2/Camera NDK interception.
        // Full frame injection (video/RTSP) will plug in here via PLT/JNI hooks.
        LOGI("postAppSpecialize active in %s source=%s virtual=%d",
             process_name.c_str(),
             control.source,
             control.virtual_cam ? 1 : 0);
    }

    void preServerSpecialize(ServerSpecializeArgs *args) override {
        (void) args;
        api->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY);
    }

private:
    Api *api = nullptr;
    JNIEnv *env = nullptr;
    std::string process_name;
    bool control_enabled = false;
    ControlState control{};
};

static void companion_handler(int client) {
    mkdir("/data/adb/vcamgd", 0755);
    std::string json;
    if (!read_file(kControlPath, json)) {
        json = "{\"enabled\":false,\"virtual\":true,\"source\":\"\",\"uri\":\"\",\"url\":\"\"}";
    }

    uint8_t flag = json_bool(json, "enabled", false) ? 1 : 0;
    write(client, &flag, 1);
    uint32_t size = static_cast<uint32_t>(json.size());
    write(client, &size, sizeof(size));
    if (size > 0) {
        write(client, json.data(), size);
    }
    LOGI("companion served control enabled=%u bytes=%u", flag, size);
}

REGISTER_ZYGISK_MODULE(VCamModule)
REGISTER_ZYGISK_COMPANION(companion_handler)
