/*
 * Privileged injector CLI. Packaged as an asset, copied to the app's files
 * dir, then executed by `su -c` so ptrace has CAP_SYS_PTRACE.
 * Usage: injector_cli <pid> <absolute-path-to-.so>
 */
#include "injector_core.h"

#include <cstdio>
#include <cstdlib>

int main(int argc, char **argv) {
    if (argc != 3) {
        fprintf(stderr, "usage: %s PID /absolute/path/to/lib.so\n", argv[0]);
        return INJECT_ERR_ARGS;
    }
    char *end = nullptr;
    long pid = strtol(argv[1], &end, 10);
    if (*end != '\0' || pid <= 0) {
        fprintf(stderr, "invalid pid: %s\n", argv[1]);
        return INJECT_ERR_ARGS;
    }
    int rc = injector_inject(static_cast<pid_t>(pid), argv[2]);
    printf("INJECT_RC=%d\n", rc);
    return rc;
}
