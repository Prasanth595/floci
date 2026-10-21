package io.github.hectorvent.floci.services.lambdaweb;

import io.github.hectorvent.floci.config.EmulatorConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * The code package each revision copied from S3 when it was created. AWS prepares its own copy at
 * creation, so a later write or delete in the bucket does not change what a revision runs.
 */
@ApplicationScoped
public class LambdaWebCode {

    private static final Logger LOG = Logger.getLogger(LambdaWebCode.class);

    /** The {@code @} keeps this tree apart from the Lambda function directories, whose names never have one. */
    private static final String ROOT = "@web-functions";

    private final Path baseDir;

    @Inject
    public LambdaWebCode(EmulatorConfig config) {
        this(Path.of(config.services().lambda().codePath()).resolve(ROOT));
    }

    LambdaWebCode(Path baseDir) {
        this.baseDir = baseDir;
    }

    void store(String accountId, String region, String functionName, String revisionId, byte[] zip) {
        Path file = file(accountId, region, functionName, revisionId);
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, zip);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not keep the code of " + functionName + " revision " + revisionId, e);
        }
    }

    /** Null for a revision stored before revisions kept their own copy. */
    byte[] read(String accountId, String region, String functionName, String revisionId) {
        try {
            return Files.readAllBytes(file(accountId, region, functionName, revisionId));
        } catch (NoSuchFileException e) {
            return null;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the code of " + functionName + " revision " + revisionId, e);
        }
    }

    void deleteRevision(String accountId, String region, String functionName, String revisionId) {
        try {
            Files.deleteIfExists(file(accountId, region, functionName, revisionId));
        } catch (IOException e) {
            LOG.warnv("Could not delete the code of {0} revision {1}: {2}", functionName, revisionId, e.getMessage());
        }
    }

    void deleteFunction(String accountId, String region, String functionName) {
        deleteTree(functionDir(accountId, region, functionName));
    }

    void clear() {
        deleteTree(baseDir);
    }

    private Path file(String accountId, String region, String functionName, String revisionId) {
        return functionDir(accountId, region, functionName).resolve(segment(revisionId) + ".zip");
    }

    private Path functionDir(String accountId, String region, String functionName) {
        return baseDir.resolve(segment(accountId)).resolve(segment(region)).resolve(segment(functionName));
    }

    // The account comes from configuration and the region from the request, so "." or ".." cannot navigate.
    private static String segment(String value) {
        String sanitized = value.replaceAll("[^a-zA-Z0-9_\\-.]", "_");
        return sanitized.isEmpty() || sanitized.chars().allMatch(c -> c == '.') ? "_" : sanitized;
    }

    private static void deleteTree(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException e) {
                    LOG.warnv("Could not delete {0}: {1}", path, e.getMessage());
                }
            });
        } catch (IOException | UncheckedIOException e) {
            LOG.warnv("Could not delete {0}: {1}", root, e.getMessage());
        }
    }
}
