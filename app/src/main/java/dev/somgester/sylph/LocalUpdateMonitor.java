package dev.somgester.sylph;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Observes this launcher's local checkout; never fetches code or changes the running app. */
final class LocalUpdateMonitor implements AutoCloseable {

    private static final List<String> INPUTS = List.of("app/src/main", "app/build.gradle", "scripts",
            "gradle", "settings.gradle", "gradle.properties", ".prototools", "init.sh", "run.sh",
            "build.sh", "dev.sh", "lint.sh", "test.sh", "gradlew", "gradlew.bat");

    private final Path project;

    private final Runnable onChange;

    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "sylph-local-updates");
        thread.setDaemon(true);
        return thread;
    });

    private final AtomicBoolean closed = new AtomicBoolean();

    private final AtomicBoolean notified = new AtomicBoolean();

    private String baseline;

    LocalUpdateMonitor(Path project, Runnable onChange) {
        this.project = project.toAbsolutePath().normalize();
        this.onChange = onChange;
    }

    void start() {
        worker.scheduleWithFixedDelay(this::checkForChanges, 0, 10, TimeUnit.SECONDS);
    }

    // Called serially by the worker; separate from scheduling for deterministic filesystem tests.
    void checkForChanges() {
        if (closed.get() || notified.get()) {
            return;
        }
        try {
            String current = fingerprint();
            if (baseline == null) {
                baseline = current;
            } else if (!baseline.equals(current) && !closed.get() && notified.compareAndSet(false, true)) {
                onChange.run();
            }
        } catch (IOException | UncheckedIOException | SecurityException unavailable) {
            // A checkout can be temporarily unreadable while Git replaces files. Retry later.
        }
    }

    private String fingerprint() throws IOException {
        List<Path> files = new ArrayList<>();
        for (String input : INPUTS) {
            Path path = project.resolve(input);
            if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                try (var paths = Files.walk(path)) {
                    paths.filter(file -> Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)).forEach(files::add);
                }
            } else if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                files.add(path);
            }
        }
        files.sort(Path::compareTo);
        MessageDigest digest = sha256();
        byte[] buffer = new byte[8192];
        for (Path file : files) {
            digest.update(project.relativize(file).toString().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            MessageDigest content = sha256();
            try (var stream = Files.newInputStream(file)) {
                int count;
                while ((count = stream.read(buffer)) != -1) {
                    content.update(buffer, 0, count);
                }
            }
            digest.update(content.digest());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
        }
    }

    @Override
    public void close() {
        closed.set(true);
        worker.shutdownNow();
    }
}
