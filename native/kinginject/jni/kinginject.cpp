#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>

#include <dlfcn.h>
#include <elf.h>
#include <errno.h>
#include <sys/mman.h>
#include <sys/ptrace.h>
#include <sys/uio.h>
#include <sys/wait.h>
#include <unistd.h>

#if defined(__aarch64__)
#include <asm/ptrace.h>
using regs_t = user_pt_regs;
#define R_ARG0(r) ((r).regs[0])
#define R_ARG1(r) ((r).regs[1])
#define R_ARG2(r) ((r).regs[2])
#define R_ARG3(r) ((r).regs[3])
#define R_ARG4(r) ((r).regs[4])
#define R_ARG5(r) ((r).regs[5])
#define R_SP(r) ((r).sp)
#define R_PC(r) ((r).pc)
#define R_LR(r) ((r).regs[30])
#define R_RET(r) ((r).regs[0])
#else
struct regs_t {
    long uregs[18];
};
#define R_ARG0(r) ((r).uregs[0])
#define R_ARG1(r) ((r).uregs[1])
#define R_ARG2(r) ((r).uregs[2])
#define R_ARG3(r) ((r).uregs[3])
#define R_SP(r) ((r).uregs[13])
#define R_PC(r) ((r).uregs[15])
#define R_LR(r) ((r).uregs[14])
#define R_RET(r) ((r).uregs[0])
#endif

static bool read_file(const char* path, std::string* out) {
    FILE* f = fopen(path, "re");
    if (!f) return false;
    char buf[4096];
    out->clear();
    while (true) {
        size_t n = fread(buf, 1, sizeof(buf), f);
        if (!n) break;
        out->append(buf, n);
    }
    fclose(f);
    return true;
}

static uintptr_t module_base(pid_t pid, const char* needle) {
    char path[64];
    snprintf(path, sizeof(path), "/proc/%d/maps", pid);
    std::string maps;
    if (!read_file(path, &maps)) return 0;
    uintptr_t best = 0;
    size_t pos = 0;
    while (pos < maps.size()) {
        size_t eol = maps.find('\n', pos);
        if (eol == std::string::npos) eol = maps.size();
        std::string line = maps.substr(pos, eol - pos);
        pos = eol + 1;
        if (line.find(needle) == std::string::npos) continue;
        if (line.find(" r-xp ") == std::string::npos && line.find(" r--p ") == std::string::npos) continue;
        uintptr_t start = strtoull(line.c_str(), nullptr, 16);
        if (start && (!best || start < best)) best = start;
    }
    return best;
}

static bool get_regs(pid_t pid, regs_t* regs) {
#if defined(__aarch64__)
    iovec io{regs, sizeof(*regs)};
    return ptrace(PTRACE_GETREGSET, pid, (void*)(uintptr_t)NT_PRSTATUS, &io) == 0;
#else
    return ptrace(PTRACE_GETREGS, pid, nullptr, regs) == 0;
#endif
}

static bool set_regs(pid_t pid, regs_t* regs) {
#if defined(__aarch64__)
    iovec io{regs, sizeof(*regs)};
    return ptrace(PTRACE_SETREGSET, pid, (void*)(uintptr_t)NT_PRSTATUS, &io) == 0;
#else
    return ptrace(PTRACE_SETREGS, pid, nullptr, regs) == 0;
#endif
}

static bool wait_stop(pid_t pid) {
    int st = 0;
    while (true) {
        if (waitpid(pid, &st, __WALL) < 0) return false;
        if (WIFSTOPPED(st)) return true;
        if (WIFEXITED(st) || WIFSIGNALED(st)) return false;
    }
}

static bool poke(pid_t pid, uintptr_t addr, const void* data, size_t len) {
    auto* p = static_cast<const uint8_t*>(data);
    size_t i = 0;
    while (i + sizeof(long) <= len) {
        long w;
        memcpy(&w, p + i, sizeof(long));
        if (ptrace(PTRACE_POKEDATA, pid, (void*)(addr + i), (void*)w) != 0) return false;
        i += sizeof(long);
    }
    if (i < len) {
        errno = 0;
        long w = ptrace(PTRACE_PEEKDATA, pid, (void*)(addr + i), nullptr);
        if (errno) return false;
        memcpy(&w, p + i, len - i);
        if (ptrace(PTRACE_POKEDATA, pid, (void*)(addr + i), (void*)w) != 0) return false;
    }
    return true;
}

static bool call_fn(pid_t pid, regs_t* saved, uintptr_t fn, uintptr_t* args, int nargs, uintptr_t* ret) {
    regs_t r = *saved;
#if defined(__aarch64__)
    if (nargs > 0) R_ARG0(r) = args[0];
    if (nargs > 1) R_ARG1(r) = args[1];
    if (nargs > 2) R_ARG2(r) = args[2];
    if (nargs > 3) R_ARG3(r) = args[3];
    if (nargs > 4) R_ARG4(r) = args[4];
    if (nargs > 5) R_ARG5(r) = args[5];
    R_SP(r) = R_SP(*saved) & ~0xfull;
#else
    if (nargs > 0) R_ARG0(r) = args[0];
    if (nargs > 1) R_ARG1(r) = args[1];
    if (nargs > 2) R_ARG2(r) = args[2];
    if (nargs > 3) R_ARG3(r) = args[3];
    R_SP(r) = R_SP(*saved) & ~0x7u;
#endif
    R_PC(r) = fn;
    R_LR(r) = 0;
    if (!set_regs(pid, &r)) return false;
    if (ptrace(PTRACE_CONT, pid, nullptr, nullptr) != 0) return false;
    if (!wait_stop(pid)) return false;
    if (!get_regs(pid, &r)) return false;
    if (ret) *ret = R_RET(r);
    return set_regs(pid, saved);
}

static uintptr_t local_sym_offset(const char* path, const char* sym) {
    void* h = dlopen(path, RTLD_NOW);
    if (!h) return 0;
    void* p = dlsym(h, sym);
    Dl_info info{};
    uintptr_t off = 0;
    if (p && dladdr(p, &info) && info.dli_fbase) {
        off = (uintptr_t)p - (uintptr_t)info.dli_fbase;
    }
    dlclose(h);
    return off;
}

static uintptr_t resolve_remote(pid_t pid, const char* sym) {
    const char* paths[] = {
#if defined(__aarch64__)
        "/apex/com.android.runtime/lib64/bionic/libdl.so",
        "/apex/com.android.runtime/lib64/bionic/libc.so",
        "/system/lib64/libdl.so",
        "/system/lib64/libc.so",
#else
        "/apex/com.android.runtime/lib/bionic/libdl.so",
        "/apex/com.android.runtime/lib/bionic/libc.so",
        "/system/lib/libdl.so",
        "/system/lib/libc.so",
#endif
        nullptr,
    };
    const char* needles[] = {"libdl.so", "libdl_android.so", "libc.so", nullptr};
    for (int i = 0; paths[i]; ++i) {
        uintptr_t off = local_sym_offset(paths[i], sym);
        if (!off) continue;
        for (int j = 0; needles[j]; ++j) {
            if (!strstr(paths[i], needles[j] + (needles[j][0] == 'l' ? 0 : 0))) {
                // try matching needle contained in path
            }
            uintptr_t base = module_base(pid, needles[j]);
            if (base) return base + off;
        }
    }
    void* p = dlsym(RTLD_DEFAULT, sym);
    Dl_info info{};
    if (p && dladdr(p, &info) && info.dli_fbase && info.dli_fname) {
        uintptr_t off = (uintptr_t)p - (uintptr_t)info.dli_fbase;
        const char* slash = strrchr(info.dli_fname, '/');
        const char* name = slash ? slash + 1 : info.dli_fname;
        uintptr_t base = module_base(pid, name);
        if (base) return base + off;
    }
    return 0;
}

static bool maps_has(pid_t pid, const char* key) {
    char path[64];
    snprintf(path, sizeof(path), "/proc/%d/maps", pid);
    std::string maps;
    if (!read_file(path, &maps)) return false;
    return maps.find(key) != std::string::npos;
}

static int inject(pid_t pid, const char* lib) {
    if (maps_has(pid, "libvc.so") || maps_has(pid, "libvc++") || maps_has(pid, lib)) {
        fprintf(stderr, "kinginject: already present\n");
        return 0;
    }

    uintptr_t dlopen_addr = resolve_remote(pid, "dlopen");
    if (!dlopen_addr) dlopen_addr = resolve_remote(pid, "android_dlopen_ext");
    uintptr_t mmap_addr = resolve_remote(pid, "mmap");
    fprintf(stderr, "kinginject: pid=%d dlopen=%p mmap=%p lib=%s\n",
            pid, (void*)dlopen_addr, (void*)mmap_addr, lib);
    if (!dlopen_addr || !mmap_addr) return 2;

    if (ptrace(PTRACE_ATTACH, pid, nullptr, nullptr) != 0) {
        fprintf(stderr, "kinginject: attach errno=%d\n", errno);
        return 3;
    }
    if (!wait_stop(pid)) {
        ptrace(PTRACE_DETACH, pid, nullptr, nullptr);
        return 4;
    }

    regs_t saved{};
    if (!get_regs(pid, &saved)) {
        ptrace(PTRACE_DETACH, pid, nullptr, nullptr);
        return 5;
    }

    uintptr_t remote = 0;
    uintptr_t mmap_args[] = {
        0, 4096, (uintptr_t)(PROT_READ | PROT_WRITE),
        (uintptr_t)(MAP_PRIVATE | MAP_ANONYMOUS), (uintptr_t)-1, 0,
    };
    if (!call_fn(pid, &saved, mmap_addr, mmap_args, 6, &remote) || !remote || remote == (uintptr_t)-1) {
        fprintf(stderr, "kinginject: mmap failed ret=%p\n", (void*)remote);
        ptrace(PTRACE_DETACH, pid, nullptr, nullptr);
        return 6;
    }

    if (!poke(pid, remote, lib, strlen(lib) + 1)) {
        ptrace(PTRACE_DETACH, pid, nullptr, nullptr);
        return 7;
    }

    uintptr_t handle = 0;
    uintptr_t dl_args[] = {remote, (uintptr_t)RTLD_NOW};
    if (!call_fn(pid, &saved, dlopen_addr, dl_args, 2, &handle)) {
        ptrace(PTRACE_DETACH, pid, nullptr, nullptr);
        return 8;
    }

    ptrace(PTRACE_DETACH, pid, nullptr, nullptr);
    fprintf(stderr, "kinginject: handle=%p maps_libvc=%d\n",
            (void*)handle, maps_has(pid, "libvc") ? 1 : 0);
    return handle ? 0 : 9;
}

int main(int argc, char** argv) {
    const char* lib = "/dev/vcam/libvc.so";
    pid_t pid = -1;
    for (int i = 1; i < argc; ++i) {
        if (!strcmp(argv[i], "--lib") && i + 1 < argc) lib = argv[++i];
        else if (!strcmp(argv[i], "--pid") && i + 1 < argc) pid = (pid_t)atoi(argv[++i]);
        else if (pid < 0 && atoi(argv[i]) > 0) pid = (pid_t)atoi(argv[i]);
        else lib = argv[i];
    }
    if (pid <= 0) {
        FILE* f = popen("pidof cameraserver", "r");
        char b[64] = {};
        if (f) {
            fgets(b, sizeof(b), f);
            pclose(f);
        }
        pid = (pid_t)atoi(b);
    }
    if (pid <= 0) {
        fprintf(stderr, "kinginject: no cameraserver\n");
        return 1;
    }
    return inject(pid, lib);
}
