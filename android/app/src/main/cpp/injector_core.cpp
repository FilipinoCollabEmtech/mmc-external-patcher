#include "injector_core.h"

#ifndef _GNU_SOURCE
#define _GNU_SOURCE 1
#endif
#include <android/log.h>
#include <dlfcn.h>
#include <cerrno>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <unistd.h>
#include <sys/mman.h>
#include <sys/ptrace.h>
#include <sys/uio.h>
#include <sys/user.h>
#include <sys/wait.h>

#define LOG_TAG "MMCInjector"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#ifndef __WALL
#define __WALL 0x40000000
#endif
#ifndef PTRACE_GETREGS
#define PTRACE_GETREGS 12
#endif
#ifndef PTRACE_SETREGS
#define PTRACE_SETREGS 13
#endif
/* Some ROMs reject PTRACE_GETREGS/SETREGS with EIO; REGSET is the modern path. */
#ifndef PTRACE_GETREGSET
#define PTRACE_GETREGSET 0x4204
#endif
#ifndef PTRACE_SETREGSET
#define PTRACE_SETREGSET 0x4205
#endif
#ifndef NT_PRSTATUS
#define NT_PRSTATUS 1
#endif

static bool get_regs(pid_t pid, struct user_regs_struct *r) {
    struct iovec iov{};
    iov.iov_base = r;
    iov.iov_len = sizeof(*r);
    if (ptrace(PTRACE_GETREGSET, pid, (void *) NT_PRSTATUS, &iov) != -1) return true;
    LOGE("GETREGSET failed: %s", strerror(errno));
    if (ptrace(PTRACE_GETREGS, pid, 0, r) != -1) return true;
    LOGE("GETREGS fallback failed: %s", strerror(errno));
    return false;
}

static bool set_regs(pid_t pid, struct user_regs_struct *r) {
    struct iovec iov{};
    iov.iov_base = r;
    iov.iov_len = sizeof(*r);
    if (ptrace(PTRACE_SETREGSET, pid, (void *) NT_PRSTATUS, &iov) != -1) return true;
    LOGE("SETREGSET failed: %s", strerror(errno));
    if (ptrace(PTRACE_SETREGS, pid, 0, r) != -1) return true;
    LOGE("SETREGS fallback failed: %s", strerror(errno));
    return false;
}

/* Region inside the target: payload + literals + path + trap + private stack. */
static const uintptr_t kRegionSize = 0x10000;
static const uintptr_t kPathOff = 0x80;
static const uintptr_t kPathCapacity = 0x300;
static const uintptr_t kTrapOff = 0x1000;

static bool read_word(pid_t pid, uintptr_t addr, uint64_t *out) {
    errno = 0;
    long value = ptrace(PTRACE_PEEKDATA, pid, reinterpret_cast<void *>(addr), nullptr);
    if (value == -1 && errno != 0) {
        LOGE("PEEKDATA 0x%llx failed: %s", (unsigned long long)addr, strerror(errno));
        return false;
    }
    *out = static_cast<uint64_t>(value);
    return true;
}

/* Reads a NUL-terminated string out of the target for dlerror() diagnostics. */
static void log_remote_string(pid_t pid, uintptr_t addr, const char *label) {
    if (!addr) { LOGE("%s: null", label); return; }
    char buf[512];
    size_t n = 0;
    uintptr_t base = addr & ~static_cast<uintptr_t>(7);
    size_t skip = addr - base;
    for (size_t off = 0; n < sizeof(buf) - 1; off += 8) {
        uint64_t word = 0;
        if (!read_word(pid, base + off, &word)) break;
        for (size_t i = skip; i < 8 && n < sizeof(buf) - 1; ++i) {
            char c = static_cast<char>((word >> (i * 8)) & 0xff);
            if (!c) { buf[n] = 0; LOGE("%s: %s", label, buf); return; }
            buf[n++] = c;
        }
        skip = 0;
    }
    buf[n] = 0;
    LOGE("%s: %s", label, buf);
}

/* All callers write 8-byte-aligned, 8-byte-multiple buffers. */
static bool write_mem(pid_t pid, uintptr_t addr, const void *buf, size_t len) {
    if ((addr & 7) || (len & 7)) return false;
    const uint8_t *bytes = static_cast<const uint8_t *>(buf);
    for (size_t off = 0; off < len; off += 8) {
        uint64_t word = 0;
        memcpy(&word, bytes + off, 8);
        if (ptrace(PTRACE_POKEDATA, pid,
                   reinterpret_cast<void *>(addr + off),
                   reinterpret_cast<void *>(word)) == -1) {
            LOGE("POKEDATA 0x%llx failed: %s",
                 (unsigned long long)(addr + off), strerror(errno));
            return false;
        }
    }
    return true;
}

/* Lowest mapping address whose pathname contains `needle` ("/libc.so"). */
static uintptr_t find_lib_base(pid_t pid, const char *needle, bool self) {
    char maps_path[64];
    if (self)
        snprintf(maps_path, sizeof(maps_path), "/proc/self/maps");
    else
        snprintf(maps_path, sizeof(maps_path), "/proc/%d/maps", pid);

    FILE *f = fopen(maps_path, "re");
    if (!f) return 0;
    uintptr_t base = 0;
    char line[512];
    while (fgets(line, sizeof(line), f)) {
        if (strstr(line, needle)) {
            uintptr_t start = strtoull(line, nullptr, 16);
            if (base == 0 || start < base) base = start;
        }
    }
    fclose(f);
    return base;
}

static bool wait_stop(pid_t pid, int timeout_ms) {
    const int iterations = timeout_ms / 10;
    for (int i = 0; i < iterations; ++i) {
        int status = 0;
        pid_t r = waitpid(pid, &status, __WALL | WNOHANG);
        if (r == pid) {
            if (WIFSTOPPED(status)) return true;
            LOGE("target did not stop (status=0x%x)", status);
            return false;
        }
        if (r == -1 && errno == ECHILD) return false;
        usleep(10000);
    }
    LOGE("timed out waiting for target stop");
    return false;
}

/* LDR (literal) Xt, #imm19 — imm19 is a signed byte offset in instructions. */
static uint32_t ldr_literal(uint32_t rt, uint32_t off_bytes) {
    uint32_t imm = (off_bytes / 4) & 0x7FFFFu;
    return 0x58000000u | (imm << 5) | rt;
}

int injector_inject(pid_t pid, const char *path) {
    if (pid <= 0 || !path || !*path) return INJECT_ERR_ARGS;
    LOGI("inject pid=%d path=%s", pid, path);

    if (ptrace(PTRACE_ATTACH, pid, 0, 0) == -1) {
        LOGE("PTRACE_ATTACH failed: %s", strerror(errno));
        return INJECT_ERR_ATTACH;
    }
    int status = 0;
    if (waitpid(pid, &status, __WALL) != pid) {
        LOGE("waitpid after attach failed: %s", strerror(errno));
        ptrace(PTRACE_DETACH, pid, 0, 0);
        return INJECT_ERR_ATTACH;
    }

    struct user_regs_struct saved;
    if (!get_regs(pid, &saved)) {
        LOGE("cannot read target registers");
        ptrace(PTRACE_DETACH, pid, 0, 0);
        return INJECT_ERR_GETREGS;
    }

    /* Compute remote addresses of mmap/dlopen from the local libc layout. */
    void *local_mmap = dlsym(RTLD_DEFAULT, "mmap");
    void *local_dlopen = dlsym(RTLD_DEFAULT, "dlopen");
    void *local_dlerror = dlsym(RTLD_DEFAULT, "dlerror");
    Dl_info mmap_info, dlopen_info, dlerror_info;
    if (!local_mmap || !local_dlopen || !local_dlerror ||
        dladdr(local_mmap, &mmap_info) == 0 ||
        dladdr(local_dlopen, &dlopen_info) == 0 ||
        dladdr(local_dlerror, &dlerror_info) == 0) {
        LOGE("cannot resolve local mmap/dlopen/dlerror offsets");
        ptrace(PTRACE_DETACH, pid, 0, 0);
        return INJECT_ERR_SYMBOLS;
    }
    uintptr_t mmap_off = (uintptr_t)local_mmap - (uintptr_t)mmap_info.dli_fbase;
    uintptr_t dlopen_off = (uintptr_t)local_dlopen - (uintptr_t)dlopen_info.dli_fbase;
    uintptr_t dlerror_off = (uintptr_t)local_dlerror - (uintptr_t)dlerror_info.dli_fbase;
    uintptr_t target_libc = find_lib_base(pid, "/libc.so", false);
    if (!target_libc) {
        LOGE("cannot find libc.so in target maps");
        ptrace(PTRACE_DETACH, pid, 0, 0);
        return INJECT_ERR_TARGET_LIBC;
    }
    uintptr_t remote_mmap = target_libc + mmap_off;
    uintptr_t remote_dlopen = target_libc + dlopen_off;
    uintptr_t remote_dlerror = target_libc + dlerror_off;
    LOGI("target libc=0x%llx mmap=0x%llx dlopen=0x%llx dlerror=0x%llx",
         (unsigned long long)target_libc,
         (unsigned long long)remote_mmap,
         (unsigned long long)remote_dlopen,
         (unsigned long long)remote_dlerror);

    int rc = INJECT_OK;

    /* Remote mmap(PROT_READ|WRITE|EXEC, MAP_PRIVATE|ANON, size=kRegionSize). */
    struct user_regs_struct r = saved;
    r.pc = remote_mmap;
    r.regs[0] = 0;
    r.regs[1] = kRegionSize;
    r.regs[2] = 7;    /* PROT_READ | PROT_WRITE | PROT_EXEC */
    r.regs[3] = 0x22; /* MAP_PRIVATE | MAP_ANONYMOUS */
    r.regs[4] = (uint64_t)-1;
    r.regs[5] = 0;
    r.regs[30] = 0;      /* return target traps -> we catch the fault */
    if (!set_regs(pid, &r)) {
        LOGE("SETREGS (mmap) failed");
        rc = INJECT_ERR_SETREGS;
        goto restore_and_detach;
    }
    ptrace(PTRACE_CONT, pid, 0, 0);
    if (!wait_stop(pid, 5000)) {
        LOGE("mmap call did not return");
        rc = INJECT_ERR_MMAP;
        goto restore_and_detach;
    }

    {
        struct user_regs_struct after_mmap;
        if (!get_regs(pid, &after_mmap)) {
            rc = INJECT_ERR_GETREGS;
            goto restore_and_detach;
        }
        uintptr_t rwx = (uintptr_t)after_mmap.regs[0];
        if (!rwx || rwx == (uintptr_t)-1) {
            LOGE("remote mmap returned 0x%llx", (unsigned long long)rwx);
            rc = INJECT_ERR_RWX;
            goto restore_and_detach;
        }
        LOGI("remote RWX region=0x%llx", (unsigned long long)rwx);

        /* Payload: x0=path, w1=RTLD_NOW(2), call dlopen, store handle.
         * LDR literals at +0x30/+0x38/+0x40; result slot at +0x48. */
        const uint32_t payload[8] = {
            ldr_literal(0, 0x30), /* 0x00 ldr x0, [pc,#0x30] -> path ptr */
            0x52800041u,          /* 0x04 mov w1, #2 (RTLD_NOW)          */
            ldr_literal(8, 0x30), /* 0x08 ldr x8, [pc,#0x30] -> dlopen   */
            0xD63F0100u,          /* 0x0c blr x8                         */
            ldr_literal(9, 0x30), /* 0x10 ldr x9, [pc,#0x30] -> slot ptr */
            0xF9000120u,          /* 0x14 str x0, [x9]                   */
            0xD65F03C0u,          /* 0x18 ret (to LR = brk trap)         */
            0xD503201Fu,          /* 0x1c nop                            */
        };
        uint64_t path_ptr = rwx + kPathOff;
        uint64_t dlopen_addr = remote_dlopen;
        uint64_t result_slot = rwx + 0x48;
        uint64_t result_zero = 0;
        /* brk #0 at trap, nop in the high half of the same 8-byte word. */
        uint64_t trap_insn = 0xD503201FD4200000ULL;

        size_t path_len = strlen(path);
        if (path_len > kPathCapacity - 1) path_len = kPathCapacity - 1;
        std::string path_buf(kPathCapacity, '\0');
        memcpy(&path_buf[0], path, path_len);

        bool wrote = write_mem(pid, rwx + 0x00, payload, sizeof(payload)) &&
                     write_mem(pid, rwx + 0x30, &path_ptr, 8) &&
                     write_mem(pid, rwx + 0x38, &dlopen_addr, 8) &&
                     write_mem(pid, rwx + 0x40, &result_slot, 8) &&
                     write_mem(pid, rwx + 0x48, &result_zero, 8) &&
                     write_mem(pid, rwx + kPathOff, path_buf.data(), path_buf.size()) &&
                     write_mem(pid, rwx + kTrapOff, &trap_insn, 8);
        if (!wrote) {
            LOGE("failed to write payload into target");
            rc = INJECT_ERR_DLOPEN_CALL;
            goto restore_and_detach;
        }

        r = saved;
        r.pc = rwx;
        r.sp = rwx + kRegionSize;   /* private, 16-byte aligned stack */
        r.regs[30] = rwx + kTrapOff;      /* brk #0 after payload returns    */
        r.regs[0] = path_ptr;
        r.regs[1] = 2;
        if (!set_regs(pid, &r)) {
            LOGE("SETREGS (payload) failed");
            rc = INJECT_ERR_SETREGS;
            goto restore_and_detach;
        }
        ptrace(PTRACE_CONT, pid, 0, 0);
        if (!wait_stop(pid, 15000)) {
            LOGE("dlopen payload did not return (target may have crashed)");
            rc = INJECT_ERR_DLOPEN_CALL;
            goto restore_and_detach;
        }

        uint64_t handle = 0;
        if (read_word(pid, rwx + 0x48, &handle)) {
            LOGI("dlopen handle=0x%llx", (unsigned long long)handle);
            if (!handle) {
                /* Ask the target for dlerror() so we know WHY the load failed. */
                struct user_regs_struct er = saved;
                er.pc = remote_dlerror;
                er.regs[30] = rwx + kTrapOff;
                er.sp = rwx + kRegionSize;
                if (set_regs(pid, &er) && wait_stop(pid, 5000)) {
                    struct user_regs_struct after;
                    if (get_regs(pid, &after)) log_remote_string(pid, after.regs[0], "dlerror");
                }
                rc = INJECT_ERR_DLOPEN_NULL;
            }
        } else {
            LOGE("cannot read dlopen result slot");
            rc = INJECT_ERR_DLOPEN_CALL;
        }
    }

restore_and_detach:
    if (!set_regs(pid, &saved))
        LOGE("SETREGS restore failed");
    if (ptrace(PTRACE_DETACH, pid, 0, 0) == -1)
        LOGE("PTRACE_DETACH failed: %s", strerror(errno));
    LOGI("inject finished rc=%d", rc);
    return rc;
}
