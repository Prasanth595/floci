package io.github.hectorvent.floci.services.lambda.zip;

import io.github.hectorvent.floci.core.common.Digests;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class DeploymentPackageTest {

    @TempDir
    Path dir;

    @Test
    void aBodyS3HoldsInMemoryIsKeptAsItIs() throws IOException {
        byte[] zip = zip("v1");
        S3Service s3 = mock(S3Service.class);
        Path staging = dir.resolve("staging");

        try (DeploymentPackage pkg = DeploymentPackage.read(object(zip, zip), staging)) {
            assertEquals(zip.length, pkg.size());
            assertEquals(Base64.getEncoder().encodeToString(Digests.sha256(zip)), pkg.sha256());
            pkg.putObject(s3, "tasks", "code.zip", "application/zip");
        }

        assertFalse(Files.exists(staging));
        verify(s3).putObject("tasks", "code.zip", zip, "application/zip", Map.of());
    }

    @Test
    void aBodyOnDiskIsStagedAndRemovedOnClose() throws IOException {
        byte[] zip = zip("v1");
        S3Service s3 = mock(S3Service.class);
        ByteArrayOutputStream stored = new ByteArrayOutputStream();
        doAnswer(inv -> {
            inv.<InputStream>getArgument(2).transferTo(stored);
            return null;
        }).when(s3).putObjectFromStream(eq("tasks"), eq("code.zip"), any(InputStream.class), eq("application/zip"),
                eq(Map.of()));
        Path staging = dir.resolve("staging");
        Path target = dir.resolve("code");

        try (DeploymentPackage pkg = DeploymentPackage.read(object(null, zip), staging)) {
            assertEquals(zip.length, pkg.size());
            assertEquals(Base64.getEncoder().encodeToString(Digests.sha256(zip)), pkg.sha256());
            assertEquals(1, entries(staging).size());
            pkg.extractTo(new ZipExtractor(), target, ZipExtractor.DEFAULT_MAX_ENTRIES, null);
            pkg.putObject(s3, "tasks", "code.zip", "application/zip");
        }

        assertEquals("v1", Files.readString(target.resolve("index.js")));
        assertArrayEquals(zip, stored.toByteArray());
        assertEquals(List.of(), entries(staging));
    }

    private static S3Service.ObjectRead object(byte[] held, byte[] body) {
        S3Object metadata = held != null
                ? new S3Object("code", "app.zip", held, "application/zip")
                : new S3Object("code", "app.zip", body.length, "application/zip", S3Object.computeETag(body));
        return new S3Service.ObjectRead(metadata, new ByteArrayInputStream(body));
    }

    private static List<Path> entries(Path dir) throws IOException {
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.toList();
        }
    }

    private static byte[] zip(String content) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("index.js"));
            zip.write(content.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return out.toByteArray();
    }
}
