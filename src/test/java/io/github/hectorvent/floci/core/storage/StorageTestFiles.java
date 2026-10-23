package io.github.hectorvent.floci.core.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** File system helpers for the storage tests. */
final class StorageTestFiles {

    private StorageTestFiles() {
    }

    /**
     * Skips the calling test unless {@code path}'s file store supports POSIX permissions. Tests
     * that make a directory read-only to force a write to fail call it first, because the POSIX
     * permission calls throw on a file system without them, such as Windows.
     */
    static void assumePosixPermissions(Path path) throws IOException {
        assumeTrue(Files.getFileStore(path).supportsFileAttributeView(PosixFileAttributeView.class),
                "POSIX file permissions are not supported here");
    }
}
