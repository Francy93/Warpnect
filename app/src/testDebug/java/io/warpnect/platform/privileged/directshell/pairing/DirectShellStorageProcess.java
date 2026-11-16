package io.warpnect.platform.privileged.directshell.pairing;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Test-only child JVM. Only fixed files within the test-owned namespace; no shell or Android work. */
public final class DirectShellStorageProcess {
    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[1]).toRealPath();
        boolean releaseAcknowledgement = args[0].equals("publish-release");
        FileChannel ownedChannel;
        FileLock ownedLock;
        try (FileChannel channel = FileChannel.open(directory.resolve("writer.lock"), StandardOpenOption.WRITE);
             FileLock lock = channel.tryLock()) {
            if (lock == null) {
                System.out.println("BUSY");
                return;
            }
            ownedChannel = channel;
            ownedLock = lock;
            boolean publish = args[0].equals("publish") || releaseAcknowledgement;
            if (publish) {
                Files.createLink(directory.resolve("identity.record"), directory.resolve("identity.pending"));
            }
            if (!lock.isValid() || !channel.isOpen()) {
                throw new IllegalStateException("fixture_lease_not_held");
            }
            if (publish) {
                System.out.println("PUBLISHED_BEFORE_RECEIPT");
            } else {
                System.out.println("HELD");
            }
            System.out.flush();
            // Parent owns termination/release. This is a process-crash model, never a power-loss test.
            int command = System.in.read();
            if (releaseAcknowledgement && command != 'R') {
                throw new IllegalStateException("fixture_release_command_invalid");
            }
        }
        if (releaseAcknowledgement) {
            if (ownedLock.isValid() || ownedChannel.isOpen()) {
                throw new IllegalStateException("fixture_resources_not_released");
            }
            System.out.println("LEASE_RELEASED");
            System.out.flush();
        }
    }
}
