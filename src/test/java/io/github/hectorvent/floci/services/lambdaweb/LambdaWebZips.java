package io.github.hectorvent.floci.services.lambdaweb;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Code packages for tests that create Web Function revisions. */
public final class LambdaWebZips {

    private LambdaWebZips() {
    }

    public static byte[] indexJs(String source) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("index.js"));
            zip.write(source.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    /** An empty {@code index.js} beside a file of zeros, so the files add up to {@code size} bytes. */
    public static byte[] unzippingTo(long size) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("index.js"));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("pad.bin"));
            byte[] zeros = new byte[1 << 20];
            for (long left = size; 0 < left; left -= zeros.length) {
                zip.write(zeros, 0, (int) Math.min(left, zeros.length));
            }
            zip.closeEntry();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }
}
