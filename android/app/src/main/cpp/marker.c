/*
 * Phase-1 PoC payload. Loaded into the target with dlopen(); its constructor
 * writes a marker file. Finding that file proves remote dlopen works inside
 * the VirtualApp game process before the full LGL mod is injected.
 */
#include <fcntl.h>
#include <unistd.h>

static void write_marker(const char *path) {
    int fd = open(path, O_WRONLY | O_CREAT | O_TRUNC, 0666);
    if (fd < 0) return;
    static const char msg[] = "MMC_INJECTOR_DLOPEN_OK\n";
    (void) write(fd, msg, sizeof(msg) - 1);
    (void) close(fd);
}

__attribute__((constructor))
static void marker_main(void) {
    /* /data/local/tmp is writable from an unprivileged app as well as root. */
    write_marker("/data/local/tmp/mmc_injector_marker.txt");
    /* Secondary path in case the app's SELinux context blocks the first. */
    write_marker("/sdcard/mmc_injector_marker.txt");
}
