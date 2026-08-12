#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>
#include <unordered_map>

#include <dlfcn.h>
#include <elf.h>
#include <errno.h>
#include <fcntl.h>
#include <sys/ptrace.h>
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
#define SP(r) ((r).sp)
#define PC(r) ((r).pc)
#define LR(r) ((r).regs[30])
#else
#error "arm64 only"
#endif

static void logi(const char* msg) {
    fprintf(stderr, "kinginject: %s\n", msg);
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
    uintptr_t base = 0;  // load bias = menor endereco de TODAS as mappings do arquivo
    std::string path;
};

/**
 * Importante no Android 10+ / 16KB:
 * libdl tem mapping r--p (ELF header) + r-xp (texto).
 * st_value e relativo ao load bias (= menor PT_LOAD), NAO ao r-xp.
 * Usar so r-xp como base faz PC cair no lugar errado:
 * call ret fica igual ao arg0 (path) e stop_sig=11.
 */
static std::vector<MapLib> find_libs(pid_t pid, const char* needle) {
    std::unordered_map<std::string, uintptr_t> min_base;
    char maps_path[64];
    snprintf(maps_path, sizeof(maps_path), "/proc/%d/maps", pid);
    std::string maps;
    if (!read_all(maps_path, &maps)) return {};
    size_t pos = 0;
    while (pos < maps.size()) {
        size_t eol = maps.find('\n', pos);
        if (eol == std::string::npos) eol = maps.size();
        std::string line = maps.substr(pos, eol - pos);
        pos = eol + 1;
        if (line.find(needle) == std::string::npos) continue;
        size_t slash = line.find('/');
        if (slash == std::string::npos) continue;
        uintptr_t start = strtoull(line.c_str(), nullptr, 16);
        if (!start) continue;
        std::string path = line.substr(slash);
        while (!path.empty() && (path.back() == ' ' || path.back() == '\r')) path.pop_back();
        auto it = min_base.find(path);
        if (it == min_base.end() || start < it->second) min_base[path] = start;
    }
    std::vector<MapLib> out;
    out.reserve(min_base.size());
    for (auto& kv : min_base) out.push_back({kv.second, kv.first});
    return out;
}

static bool maps_has_libvc(pid_t pid) {
    char maps_path[64];
    snprintf(maps_path, sizeof(maps_path), "/proc/%d/maps", pid);
    std::string maps;
    if (!read_all(maps_path, &maps)) return false;
    return maps.find("libvc.so") != std::string::npos ||
           maps.find("/dev/vcam/") != std::string::npos ||
           maps.find("libvc++.so") != std::string::npos ||
           maps.find("libshadowhook.so") != std::string::npos;
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
        if (shdr[i].sh_type == SHT_DYNSYM) {
            dynsym = &shdr[i];
            if (dynsym->sh_link < (Elf64_Word)ehdr->e_shnum) dynstr = &shdr[dynsym->sh_link];
            break;
        }
    }
    if (!dynsym || !dynstr) return 0;
    const char* strs = data.data() + dynstr->sh_offset;
    size_t count = dynsym->sh_size / sizeof(Elf64_Sym);
    auto* syms = reinterpret_cast<const Elf64_Sym*>(data.data() + dynsym->sh_offset);
    for (size_t i = 0; i < count; ++i) {
        if (!syms[i].st_name) continue;
        if (strcmp(strs + syms[i].st_name, sym) == 0 && syms[i].st_value) {
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

static bool wait_stop(pid_t pid, int* sig_out) {
    int st = 0;
    while (true) {
        if (waitpid(pid, &st, __WALL) < 0) return false;
        if (WIFSTOPPED(st)) {
            if (sig_out) *sig_out = WSTOPSIG(st);
            return true;
        }
        if (WIFEXITED(st) || WIFSIGNALED(st)) return false;
    }
}

static bool poke(pid_t pid, uintptr_t addr, const void* data, size_t len) {
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

static bool peek(pid_t pid, uintptr_t addr, void* data, size_t len) {
    iovec local{data, len};
    iovec remote{(void*)addr, len};
    ssize_t n = process_vm_readv(pid, &local, 1, &remote, 1, 0);
    return n == (ssize_t)len;
}

static bool remote_call(pid_t pid, regs_t* saved, uintptr_t fn, uintptr_t* args, int nargs, uintptr_t* ret, int* sig_out) {
    regs_t r = *saved;
    if (nargs > 0) R0(r) = args[0];
    if (nargs > 1) R1(r) = args[1];
    if (nargs > 2) R2(r) = args[2];
    if (nargs > 3) R3(r) = args[3];
    if (nargs > 4) R4(r) = args[4];
    if (nargs > 5) R5(r) = args[5];
    SP(r) = (SP(*saved) - 0x200) & ~0xfull;
    PC(r) = fn;
    LR(r) = 0;
    if (!set_regs(pid, &r)) return false;
    if (ptrace(PTRACE_CONT, pid, nullptr, nullptr) != 0) return false;
    int sig = 0;
    if (!wait_stop(pid, &sig)) return false;
    if (!get_regs(pid, &r)) return false;
    if (ret) *ret = R0(r);
    if (sig_out) *sig_out = sig;
    fprintf(stderr, "kinginject: call pc=%p ret=%p stop_sig=%d\n", (void*)fn, (void*)R0(r), sig);
    fflush(stderr);
    return set_regs(pid, saved);
}

static uintptr_t resolve_sym(pid_t pid, const char* needle, const char* sym) {
    auto libs = find_libs(pid, needle);
    for (auto& lib : libs) {
        uintptr_t off = elf_sym_offset(lib.path.c_str(), sym);
        if (!off) continue;
        uintptr_t addr = lib.base + off;
        // Sanity: primeiros bytes remotos devem ser codigo (BTI/PAC/STP), nao zero
        uint32_t insn = 0;
        if (peek(pid, addr, &insn, sizeof(insn))) {
            fprintf(stderr, "kinginject: %s!%s base=%p off=%p addr=%p insn=0x%08x path=%s\n",
                    needle, sym, (void*)lib.base, (void*)off, (void*)addr, insn, lib.path.c_str());
        } else {
            fprintf(stderr, "kinginject: %s!%s base=%p off=%p addr=%p (peek fail) path=%s\n",
                    needle, sym, (void*)lib.base, (void*)off, (void*)addr, lib.path.c_str());
        }
        fflush(stderr);
        return addr;
    }
    return 0;
}

static uintptr_t resolve_dlopen(pid_t pid) {
    const char* syms[] = {"dlopen", "__loader_dlopen", "android_dlopen_ext", nullptr};
    const char* libs[] = {"libdl_android.so", "libdl.so", "linker64", nullptr};
    for (int l = 0; libs[l]; ++l) {
        for (int s = 0; syms[s]; ++s) {
            uintptr_t a = resolve_sym(pid, libs[l], syms[s]);
            if (a) return a;
        }
    }
    return 0;
}

static int inject(pid_t pid, const char* lib) {
    fprintf(stderr, "kinginject: target pid=%d lib=%s\n", pid, lib);
    fflush(stderr);
    if (maps_has_libvc(pid)) {
        logi("already loaded");
        return 0;
    }
    if (access(lib, R_OK) != 0) {
        logi("lib not readable");
        return 10;
    }

    // Precisa attach antes do peek de sanity? peek usa process_vm_readv — funciona sem attach.
    uintptr_t dlopen_addr = resolve_dlopen(pid);
    if (!dlopen_addr) {
        logi("resolve dlopen failed");
        return 2;
    }

    if (ptrace(PTRACE_ATTACH, pid, nullptr, nullptr) != 0) {
        fprintf(stderr, "kinginject: ATTACH failed errno=%d\n", errno);
        return 3;
    }
    int sig = 0;
    if (!wait_stop(pid, &sig)) {
        ptrace(PTRACE_DETACH, pid, nullptr, nullptr);
        logi("wait attach failed");
        return 4;
    }
    fprintf(stderr, "kinginject: attached stop_sig=%d\n", sig);
    fflush(stderr);

    regs_t saved{};
    if (!get_regs(pid, &saved)) {
        ptrace(PTRACE_DETACH, pid, nullptr, nullptr);
        logi("getregs failed");
        return 5;
    }

    const size_t path_len = strlen(lib) + 1;
    uintptr_t remote_path = (SP(saved) - 0x800 - path_len) & ~0xfull;
    if (!poke(pid, remote_path, lib, path_len)) {
        remote_path = (SP(saved) - 0x1000) & ~0xfull;
        if (!poke(pid, remote_path, lib, path_len)) {
            ptrace(PTRACE_DETACH, pid, nullptr, nullptr);
            logi("poke stack path failed");
            return 7;
        }
    }
    fprintf(stderr, "kinginject: path@stack=%p\n", (void*)remote_path);
    fflush(stderr);

    uintptr_t handle = 0;
    int call_sig = 0;
    uintptr_t args[] = {remote_path, (uintptr_t)RTLD_NOW};
    if (!remote_call(pid, &saved, dlopen_addr, args, 2, &handle, &call_sig)) {
        ptrace(PTRACE_DETACH, pid, nullptr, nullptr);
        logi("dlopen call failed");
        return 8;
    }

    // Se ret == arg0, a funcao nao rodou (PC errado / fault imediato)
    if (handle == remote_path) {
        fprintf(stderr, "kinginject: bogus ret==path (bad PC). retry __loader_dlopen\n");
        fflush(stderr);
        uintptr_t alt = resolve_sym(pid, "linker64", "__loader_dlopen");
        if (!alt) alt = resolve_sym(pid, "libdl_android.so", "__loader_dlopen");
        if (alt && alt != dlopen_addr) {
            handle = 0;
            remote_call(pid, &saved, alt, args, 2, &handle, &call_sig);
        }
    }

    if (!handle || handle == remote_path) {
        uintptr_t args2[] = {remote_path, (uintptr_t)(RTLD_NOW | 0x100)};
        uintptr_t h2 = 0;
        remote_call(pid, &saved, dlopen_addr, args2, 2, &h2, &call_sig);
        if (h2 && h2 != remote_path) handle = h2;
    }

    ptrace(PTRACE_DETACH, pid, nullptr, nullptr);

    bool ok = maps_has_libvc(pid);
    fprintf(stderr, "kinginject: handle=%p maps_libvc=%d\n", (void*)handle, ok ? 1 : 0);
    fflush(stderr);
    if (ok) return 0;
    if (!handle || handle == remote_path) return 9;
    return 11;
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
        logi("no cameraserver");
        return 1;
    }
    return inject(pid, lib);
}
