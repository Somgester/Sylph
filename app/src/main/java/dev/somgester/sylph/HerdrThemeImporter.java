package dev.somgester.sylph;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;

/** Runs the bundled import workflow without depending on the launch working directory. */
final class HerdrThemeImporter {

    /** Messages are fixed here; subprocess output may contain credentials or private paths. */
    static final class ImportFailure extends IOException {

        private static final long serialVersionUID = 1L;

        private ImportFailure(String message) {
            super(message + " Previous theme was kept.");
        }
    }

    static ImportFailure failureForExit(int code) {
        return new ImportFailure(switch (code) {
            case 3 -> "Invalid theme or URL. Use a raw HTTPS URL or theme-only TOML with supported names and colors.";
            case 4 -> "Python 3.11 or newer is required to import themes. Install it and retry.";
            case 5 -> "curl is required to download themes. Install it, or browse for a local TOML file.";
            case 6 -> "Theme download failed. Check the raw HTTPS URL, connection, and 64 KiB size limit.";
            case 7 -> "Cannot read or install the theme file. Check that it exists and has accessible permissions.";
            default -> "Theme import failed. Check the file or raw HTTPS URL and retry.";
        });
    }

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
                        throw new ImportFailure("The Herdr import scripts are missing. Rebuild or reinstall Sylph.");
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
            try {
                process = builder.start();
            } catch (IOException failure) {
                throw new ImportFailure("Cannot start the import workflow. Check that Bash is installed "
                        + "and available.");
            }
            if (!process.waitFor(90, TimeUnit.SECONDS)) {
                throw new ImportFailure("Theme import timed out. Check the connection and retry.");
            }
            if (process.exitValue() != 0) {
                throw failureForExit(process.exitValue());
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
