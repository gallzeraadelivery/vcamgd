#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include <dlfcn.h>
#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/ptrace.h>
#include <sys/syscall.h>
#include <sys/uio.h>
#include <sys/wait.h>
#include <unistd.h>

#if defined(__aarch64__)
#include <asm/ptrace.h>
using regs_t = user_pt_regs;
#define R0(r) ((r).regs[0])
#define R1(r) ((r).regs[1])
#define R2(r) ((r).regs[2])
#define R3(r) ((r).regs[3])
#define R4(r) ((r).regs[4])
#define R5(r) ((r).regs[5])
#define R8(r) ((r).regs[8])
#define SP(r) ((r).sp)
#define PC(r) ((r).pc)
#define LR(r) ((r).regs[30])
#else
#error "arm64 only build used on target device"
#endif

static void die_log(const char* msg, int code) {
    fprintf(stderr, "kinginject: %s (code=%d errno=%d)\n", msg, code, errno);
    fflush(stderr);
}

static bool read_all(const char* path, std::string* out) {
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return false;
    char buf[8192];
    out->clear();
    while (true) {
        ssize_t n = read(fd, buf, sizeof(buf));
        if (n < 0) {
            close(fd);
            return false;
        }
        if (n == 0) break;
        out->append(buf, (size_t)n);
    }
    close(fd);
    return true;
}

struct MapLib {
    uintptr_t base = 0;
    std::string path;
};

static std::vector<MapLib> find_libs(pid_t pid, const char* needle) {
    std::vector<MapLib> out;
    char maps_path[64];
    snprintf(maps_path, sizeof(maps_path), "/proc/%d/maps", pid);
    std::string maps;
    if (!read_all(maps_path, &maps)) return out;
    size_t pos = 0;
    while (pos < maps.size()) {
        size_t eol = maps.find('\n', pos);
        if (eol == std::string::npos) eol = maps.size();
        std::string line = maps.substr(pos, eol - pos);
        pos = eol + 1;
        if (line.find(needle) == std::string::npos) continue;
        if (line.find(" r-xp ") == std::string::npos && line.find(" r--p ") == std::string::npos) continue;
        uintptr_t start = strtoull(line.c_str(), nullptr, 16);
        size_t slash = line.find('/');
        if (!start || slash == std::string::npos) continue;
        std::string path = line.substr(slash);
        // trim
        while (!path.empty() && (path.back() == ' ' || path.back() == '\r')) path.pop_back();
        bool exists = false;
        for (auto& m : out) {
            if (m.path == path) {
                exists = true;
                if (start < m.base) m.base = start;
                break;
            }
        }
        if (!exists) out.push_back({start, path});
    }
    return out;
}

static bool maps_has(pid_t pid, const char* key) {
    char maps_path[64];
    snprintf(maps_path, sizeof(maps_path), "/proc/%d/maps", pid);
    std::string maps;
    if (!read_all(maps_path, &maps)) return false;
    return maps.find(key) != std::string::npos;
}

static uintptr_t elf_sym_offset(const char* path, const char* sym) {
    std::string data;
    if (!read_all(path, &data) || data.size() < sizeof(Elf64_Ehdr)) return 0;
    auto* ehdr = reinterpret_cast<const Elf64_Ehdr*>(data.data());
    if (memcmp(ehdr->e_ident, ELFMAG, SELFMAG) != 0 || ehdr->e_ident[EI_CLASS] != ELFCLASS64) return 0;
    auto* shdr = reinterpret_cast<const Elf64_Shdr*>(data.data() + ehdr->e_shoff);
    const Elf64_Shdr* dynsym = nullptr;
    const Elf64_Shdr* dynstr = nullptr;
    for (int i = 0; i < ehdr->e_shnum; ++i) {
        if (shdr[i].sh_type == SHT_DYNSYM) dynsym = &shdr[i];
        if (shdr[i].sh_type == SHT_STRTAB && dynsym &&
            &shdr[dynsym->sh_link] == &shdr[i]) {
            // handled below
        }
    }
    for (int i = 0; i < ehdr->e_shnum; ++i) {
        if (shdr[i].sh_type == SHT_DYNSYM) {
            dynsym = &shdr[i];
            if (dynsym->sh_link < ehdr->e_shnum) dynstr = &shdr[dynsym->sh_link];
            break;
        }
    }
    if (!dynsym || !dynstr) return 0;
    const char* strs = data.data() + dynstr->sh_offset;
    size_t count = dynsym->sh_size / sizeof(Elf64_Sym);
    auto* syms = reinterpret_cast<const Elf64_Sym*>(data.data() + dynsym->sh_offset);
    for (size_t i = 0; i < count; ++i) {
        if (!syms[i].st_name) continue;
        const char* name = strs + syms[i].st_name;
        if (strcmp(name, sym) == 0 && syms[i].st_value) {
            return (uintptr_t)syms[i].st_value;
        }
    }
    return 0;
}

static bool get_regs(pid_t pid, regs_t* regs) {
    iovec io{regs, sizeof(*regs)};
    return ptrace(PTRACE_GETREGSET, pid, (void*)(uintptr_t)NT_PRSTATUS, &io) == 0;
}

static bool set_regs(pid_t pid, regs_t* regs) {
    iovec io{regs, sizeof(*regs)};
    return ptrace(PTRACE_SETREGSET, pid, (void*)(uintptr_t)NT_PRSTATUS, &io) == 0;
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
    // Prefer process_vm_writev
    iovec local{(void*)data, len};
    iovec remote{(void*)addr, len};
    ssize_t n = process_vm_writev(pid, &local, 1, &remote, 1, 0);
    if (n == (ssize_t)len) return true;

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

// Make remote call; LR=0 causes SIGSEGV stop after return.
static bool remote_call(pid_t pid, regs_t* saved, uintptr_t fn, uintptr_t* args, int nargs, uintptr_t* ret) {
    regs_t r = *saved;
    if (nargs > 0) R0(r) = args[0];
    if (nargs > 1) R1(r) = args[1];
    if (nargs > 2) R2(r) = args[2];
    if (nargs > 3) R3(r) = args[3];
    if (nargs > 4) R4(r) = args[4];
    if (nargs > 5) R5(r) = args[5];
    SP(r) = SP(*saved) & ~0xfull;
    PC(r) = fn;
    LR(r) = 0;
    if (!set_regs(pid, &r)) return false;
    if (ptrace(PTRACE_CONT, pid, nullptr, nullptr) != 0) return false;
    if (!wait_stop(pid)) return false;
    if (!get_regs(pid, &r)) return false;
    if (ret) *ret = R0(r);
    return set_regs(pid, saved);
}

// Remote mmap via direct syscall (x8=__NR_mmap), avoids needing libc symbol.
static bool remote_mmap_syscall(pid_t pid, regs_t* saved, size_t len, uintptr_t* out) {
    // Find a SVC gadget: scan libc executable for "svc #0; ret" bytes: 01 00 00 d4 c0 03 5f d6
    auto libcs = find_libs(pid, "libc.so");
    if (libcs.empty()) {
        die_log("no libc in remote", 21);
        return false;
    }
    uintptr_t svc = 0;
    for (auto& lib : libcs) {
        std::string blob;
        if (!read_all(lib.path.c_str(), &blob)) continue;
        const uint8_t pat[] = {0x01, 0x00, 0x00, 0xd4};  // svc #0
        for (size_t i = 0; i + 4 < blob.size(); ++i) {
            if (memcmp(blob.data() + i, pat, 4) == 0) {
                svc = lib.base + i;
                break;
            }
        }
        if (svc) break;
    }
    if (!svc) {
        // fallback: use libc mmap symbol
        for (auto& lib : libcs) {
            uintptr_t off = elf_sym_offset(lib.path.c_str(), "mmap");
            if (off) {
                uintptr_t args[] = {
                    0, len, (uintptr_t)(PROT_READ | PROT_WRITE),
                    (uintptr_t)(MAP_PRIVATE | MAP_ANONYMOUS), (uintptr_t)-1, 0,
                };
                return remote_call(pid, saved, lib.base + off, args, 6, out);
            }
        }
        die_log("no svc gadget/mmap", 22);
        return false;
    }

    regs_t r = *saved;
    R0(r) = 0;
    R1(r) = len;
    R2(r) = PROT_READ | PROT_WRITE;
    R3(r) = MAP_PRIVATE | MAP_ANONYMOUS;
    R4(r) = (uint64_t)-1;
    R5(r) = 0;
    R8(r) = __NR_mmap;
    PC(r) = svc;
    LR(r) = 0;
    SP(r) = SP(*saved) & ~0xfull;
    if (!set_regs(pid, &r)) return false;
    if (ptrace(PTRACE_CONT, pid, nullptr, nullptr) != 0) return false;
    if (!wait_stop(pid)) return false;
    if (!get_regs(pid, &r)) return false;
    *out = R0(r);
    return set_regs(pid, saved) && *out && *out != (uintptr_t)-1;
}

static uintptr_t resolve_dlopen(pid_t pid) {
    const char* names[] = {"dlopen", "android_dlopen_ext", "__loader_dlopen", nullptr};
    const char* needles[] = {"libdl_android.so", "libdl.so", "linker64", "linker", nullptr};
    for (int n = 0; needles[n]; ++n) {
        auto libs = find_libs(pid, needles[n]);
        for (auto& lib : libs) {
            for (int s = 0; names[s]; ++s) {
                uintptr_t off = elf_sym_offset(lib.path.c_str(), names[s]);
                if (off) {
                    fprintf(stderr, "kinginject: dlopen via %s!%s base=%p off=%p\n",
                            lib.path.c_str(), names[s], (void*)lib.base, (void*)off);
                    return lib.base + off;
                }
            }
        }
    }
    return 0;
}

static int inject(pid_t pid, const char* lib) {
    fprintf(stderr, "kinginject: target pid=%d lib=%s\n", pid, lib);
    if (maps_has(pid, "libvc.so") || maps_has(pid, lib)) {
        fprintf(stderr, "kinginject: already loaded\n");
        return 0;
    }
    if (access(lib, R_OK) != 0) {
        die_log("lib not readable", 10);
        return 10;
    }

    uintptr_t dlopen_addr = resolve_dlopen(pid);
    if (!dlopen_addr) {
        die_log("resolve dlopen failed", 2);
        return 2;
    }

    if (ptrace(PTRACE_ATTACH, pid, nullptr, nullptr) != 0) {
        die_log("PTRACE_ATTACH failed", 3);
        return 3;
    }
    if (!wait_stop(pid)) {
        ptrace(PTRACE_DETACH, pid, nullptr, nullptr);
        die_log("wait attach failed", 4);
        return 4;
    }

    regs_t saved{};
    if (!get_regs(pid, &saved)) {
        ptrace(PTRACE_DETACH, pid, nullptr, nullptr);
        die_log("getregs failed", 5);
        return 5;
    }

    uintptr_t remote = 0;
    if (!remote_mmap_syscall(pid, &saved, 4096, &remote)) {
        ptrace(PTRACE_DETACH, pid, nullptr, nullptr);
        die_log("remote mmap failed", 6);
        return 6;
    }
    fprintf(stderr, "kinginject: remote_buf=%p\n", (void*)remote);

    if (!poke(pid, remote, lib, strlen(lib) + 1)) {
        ptrace(PTRACE_DETACH, pid, nullptr, nullptr);
        die_log("poke path failed", 7);
        return 7;
    }

    uintptr_t handle = 0;
    uintptr_t args[] = {remote, (uintptr_t)RTLD_NOW};
    if (!remote_call(pid, &saved, dlopen_addr, args, 2, &handle)) {
        ptrace(PTRACE_DETACH, pid, nullptr, nullptr);
        die_log("dlopen call failed", 8);
        return 8;
    }

    ptrace(PTRACE_DETACH, pid, nullptr, nullptr);
    bool in_maps = maps_has(pid, "libvc") || maps_has(pid, lib);
    fprintf(stderr, "kinginject: handle=%p maps=%d\n", (void*)handle, in_maps ? 1 : 0);
    if (!handle) return 9;
    return in_maps ? 0 : 11;
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
        die_log("no cameraserver", 1);
        return 1;
    }
    return inject(pid, lib);
}
