#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>

#include <string>

/** KingVCam daemon — IPC unix socket + control.json para hooks Zygisk/soft. */

static const char* kDir = "/data/local/tmp/vcamgd";
static const char* kSock = "/data/local/tmp/vcamgd/kingvd.sock";
static const char* kControl = "/data/local/tmp/vcamgd/control.json";
static const char* kStatus = "/data/local/tmp/vcamgd/status.json";
static const char* kVideo = "/data/local/tmp/vcamgd/current.mp4";
static const char* kAdbDir = "/data/adb/vcamgd";
static const char* kAdbControl = "/data/adb/vcamgd/control.json";

static volatile sig_atomic_t g_running = 1;
static std::string g_source_path;
static bool g_enabled = false;
static bool g_virtual = true;

static void on_signal(int) { g_running = 0; }

static void ensure_dirs() {
    mkdir(kDir, 0777);
    mkdir(kAdbDir, 0777);
    chmod(kDir, 0777);
    chmod(kAdbDir, 0777);
}

static bool write_file(const char* path, const std::string& body) {
    int fd = open(path, O_WRONLY | O_CREAT | O_TRUNC, 0666);
    if (fd < 0) return false;
    const char* p = body.c_str();
    size_t left = body.size();
    while (left > 0) {
        ssize_t n = write(fd, p, left);
        if (n < 0) {
            close(fd);
            return false;
        }
        p += n;
        left -= (size_t)n;
    }
    fchmod(fd, 0666);
    close(fd);
    return true;
}

static void write_status(const char* msg) {
    char buf[512];
    snprintf(buf, sizeof(buf),
             "{\"engine\":\"kingvd\",\"feeder\":\"%s\",\"ts\":%lld}",
             msg, (long long)time(nullptr));
    write_file(kStatus, buf);
}

static void write_control() {
    // JSON minimo lido pelo HookEntry
    char buf[1024];
    const char* mode = (!g_enabled) ? "real" : (g_virtual ? "virtual" : "real");
    const char* source = "local";
    std::string url;
    std::string uri = g_source_path;
    if (uri.rfind("rtsp", 0) == 0 || uri.rfind("http", 0) == 0 || uri.rfind("rtmp", 0) == 0) {
        source = "network";
        url = uri;
        uri.clear();
    } else if (!uri.empty() && uri[0] == '/') {
        // copia path para current.mp4 se for arquivo local distinto
        // (o app ja stageia current.mp4; so referencia)
        if (uri != kVideo) {
            // best-effort symlink/copy hint via path no json
        }
    }
    snprintf(buf, sizeof(buf),
             "{\"enabled\":%s,\"virtual\":%s,\"mode\":\"%s\",\"inject\":\"soft\","
             "\"source\":\"%s\",\"uri\":\"%s\",\"url\":\"%s\",\"engine\":\"kingvd\"}",
             g_enabled ? "true" : "false",
             g_virtual ? "true" : "false",
             mode,
             source,
             uri.c_str(),
             url.c_str());
    write_file(kControl, buf);
    write_file(kAdbControl, buf);
    write_status(g_enabled ? (g_virtual ? "virtual_on" : "real_on") : "disabled");
}

static std::string handle_line(const std::string& line) {
    if (line == "PING") return "OK kingvd";
    if (line == "STATUS") {
        char buf[256];
        snprintf(buf, sizeof(buf), "OK enabled=%d virtual=%d path=%s",
                 g_enabled ? 1 : 0, g_virtual ? 1 : 0, g_source_path.c_str());
        return buf;
    }
    if (line == "STOP") {
        g_enabled = false;
        g_virtual = false;
        write_control();
        return "OK stopped";
    }
    if (line == "REAL") {
        g_enabled = true;
        g_virtual = false;
        write_control();
        return "OK real";
    }
    if (line == "VIRTUAL") {
        g_enabled = true;
        g_virtual = true;
        write_control();
        return "OK virtual";
    }
    if (line.rfind("PLAY ", 0) == 0) {
        std::string path = line.substr(5);
        while (!path.empty() && (path.back() == '\r' || path.back() == '\n' || path.back() == ' '))
            path.pop_back();
        if (path.empty()) return "ERR empty path";
        g_source_path = path;
        g_enabled = true;
        g_virtual = true;
        write_control();
        return "OK playing";
    }
    return "ERR unknown";
}

static void serve_client(int cfd) {
    std::string acc;
    char buf[512];
    while (g_running) {
        ssize_t n = read(cfd, buf, sizeof(buf));
        if (n <= 0) break;
        acc.append(buf, buf + n);
        size_t pos;
        while ((pos = acc.find('\n')) != std::string::npos) {
            std::string line = acc.substr(0, pos);
            acc.erase(0, pos + 1);
            while (!line.empty() && line.back() == '\r') line.pop_back();
            if (line.empty()) continue;
            std::string resp = handle_line(line);
            resp.push_back('\n');
            write(cfd, resp.data(), resp.size());
            if (line == "QUIT") {
                close(cfd);
                return;
            }
        }
    }
    close(cfd);
}

int main(int argc, char** argv) {
    (void)argc;
    (void)argv;
    signal(SIGPIPE, SIG_IGN);
    signal(SIGTERM, on_signal);
    signal(SIGINT, on_signal);

    ensure_dirs();
    unlink(kSock);

    int sfd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (sfd < 0) {
        perror("socket");
        return 1;
    }

    sockaddr_un addr{};
    addr.sun_family = AF_UNIX;
    strncpy(addr.sun_path, kSock, sizeof(addr.sun_path) - 1);
    if (bind(sfd, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) < 0) {
        perror("bind");
        return 1;
    }
    chmod(kSock, 0666);
    if (listen(sfd, 8) < 0) {
        perror("listen");
        return 1;
    }

    // estado inicial: camera real
    g_enabled = false;
    g_virtual = false;
    write_control();
    write_status("listening");

    while (g_running) {
        int cfd = accept(sfd, nullptr, nullptr);
        if (cfd < 0) {
            if (errno == EINTR) continue;
            break;
        }
        serve_client(cfd);
    }

    close(sfd);
    unlink(kSock);
    write_status("exited");
    return 0;
}
