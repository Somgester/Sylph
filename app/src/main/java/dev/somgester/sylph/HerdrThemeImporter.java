package dev.somgester.sylph;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;

/** Runs the bundled import workflow without depending on the launch working directory. */
final class HerdrThemeImporter {

    private final Path profile;

    HerdrThemeImporter(Path profile) {
        this.profile = profile.toAbsolutePath();
    }

    void importTheme(String source) throws IOException, InterruptedException {
        Path directory = Files.createTempDirectory("sylph-herdr-import-");
        Process process = null;
        try {
            for (String name : new String[] {"import-herdr-theme.sh", "validate-herdr-theme.py"}) {
                try (InputStream stream = HerdrThemeImporter.class.getResourceAsStream("/herdr/" + name)) {
                    if (stream == null) {
                        throw new IOException("The Herdr import scripts are missing from this build.");
                    }
                    Files.copy(stream, directory.resolve(name));
                }
            }
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException();
            }
            ProcessBuilder builder = new ProcessBuilder("bash", directory.resolve("import-herdr-theme.sh").toString(),
                    source,
                    profile.resolve("imported-theme.toml").toString())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD);
            builder.environment().put("TMPDIR", directory.toString());
            process = builder.start();
            if (!process.waitFor(90, TimeUnit.SECONDS)) {
                throw new IOException("Theme import timed out. Previous theme was kept.");
            }
            if (process.exitValue() != 0) {
                throw new IOException("Import failed. Check the file or raw HTTPS URL, theme format, "
                        + "and Python 3.11+/curl installation. Previous theme was kept.");
            }
        } finally {
            if (process != null && process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                // Wait for cleanup without swallowing a cancellation interrupt.
                process.onExit().join();
            }
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }
}
