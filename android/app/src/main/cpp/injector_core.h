#ifndef MMC_INJECTOR_CORE_H
#define MMC_INJECTOR_CORE_H

#include <sys/types.h>

/* Return codes shared by the JNI wrapper and the su-run CLI. */
enum InjectResult {
    INJECT_OK = 0,
    INJECT_ERR_ARGS = -1,
    INJECT_ERR_ATTACH = -2,
    INJECT_ERR_GETREGS = -3,
    INJECT_ERR_SYMBOLS = -4,
    INJECT_ERR_TARGET_LIBC = -5,
    INJECT_ERR_MMAP = -6,
    INJECT_ERR_RWX = -7,
    INJECT_ERR_DLOPEN_CALL = -8,
    INJECT_ERR_DLOPEN_NULL = -9,
    INJECT_ERR_SETREGS = -10,
};

/*
 * Attach to `pid`, allocate RWX memory in it, and call dlopen(path, RTLD_NOW)
 * there via a hand-built AArch64 payload. Must run with CAP_SYS_PTRACE
 * (i.e. under su). Safe to call repeatedly.
 */
int injector_inject(pid_t pid, const char *path);

/* Path-less load: copy the .so bytes into the target, memfd_create there, and
 * dlopen("/proc/self/fd/<fd>"). Avoids filesystem/SELinux path restrictions. */
int injector_inject_memfd(pid_t pid, const char *local_so_path);

#endif
