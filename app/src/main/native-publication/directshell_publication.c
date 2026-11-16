#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <stdint.h>
#include <stdio.h>
#include <sys/stat.h>
#include <unistd.h>

static jlong knowledge(uint32_t code, int error) {
    return (jlong) (((uint64_t) (uint32_t) error << 32) | code);
}

/* Private API30 library, never linked into min26 SCL. Borrowed FD; fixed encrypted-record names. */
JNIEXPORT jlong JNICALL
Java_io_warpnect_platform_privileged_directshell_pairing_DirectShellPublicationNativeEntry_publishBorrowedDirectory(
        JNIEnv *env, jobject self, jint directory_fd) {
    (void) env;
    (void) self;
    struct stat directory, source;
    const uid_t owner = getuid();
    if (fstat(directory_fd, &directory) != 0) return knowledge(4, errno);
    if (!S_ISDIR(directory.st_mode) || directory.st_uid != owner || (directory.st_mode & 0077)) {
        return knowledge(4, EACCES);
    }
    if (fstatat(directory_fd, "identity.pending", &source, AT_SYMLINK_NOFOLLOW) != 0) {
        return knowledge(4, errno);
    }
    if (!S_ISREG(source.st_mode) || source.st_uid != owner || (source.st_mode & 0177) ||
            source.st_nlink != 1 || source.st_size <= 0 || source.st_size > 65536) {
        return knowledge(4, EACCES);
    }
    const int result = renameat2(directory_fd, "identity.pending", directory_fd,
                                "identity.record", RENAME_NOREPLACE);
    const int error = result == 0 ? 0 : errno;
    if (result == 0) return knowledge(1, 0);
    if (error == EEXIST) return knowledge(2, error);
    if (error == ENOSYS || error == EOPNOTSUPP || error == EINVAL || error == EACCES || error == EPERM) {
        return knowledge(3, error);
    }
    return knowledge(5, error);
}
