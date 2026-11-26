package dev.somgester.sylph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HerdrThemeImporterTest {

    @TempDir
    Path directory;

    @Test
    void bundledScriptImportsAndAppliesCustomColorsWithoutChangingInheritedEnvironment() throws Exception {
        requireImporterTools();
        Path source = directory.resolve("theme with spaces.toml");
        Path profile = directory.resolve("profile with spaces");
        Files.writeString(source, "[theme]\nname = \"nord\"\n[theme.custom]\naccent = \"#aBc\"\n");
        new HerdrThemeImporter(profile).importTheme(source.toString());
        Map<String, String> inherited = Map.of("PATH", "original", "HERDR_SESSION", "old-session",
                "HERDR_SOCKET_PATH", "old-socket");
        Map<String, String> environment = HerdrCliTheme.IMPORTED.environment(profile, inherited);
        assertEquals(profile.resolve("imported-theme.toml").toString(), environment.get("HERDR_CONFIG_PATH"));
        assertTrue(Files.readString(Path.of(environment.get("HERDR_CONFIG_PATH"))).contains("accent = \"#abc\""));
        assertEquals("original", environment.get("PATH"));
        assertFalse(environment.containsKey("HERDR_SESSION"));
        assertFalse(environment.containsKey("HERDR_SOCKET_PATH"));
        assertEquals("old-session", inherited.get("HERDR_SESSION"));
        assertEquals(profile.resolve("state").toString(), environment.get("XDG_STATE_HOME"));
        HerdrCliTheme.DRACULA.environment(profile, inherited);
        assertTrue(Files.readString(profile.resolve("imported-theme.toml")).contains("accent = \"#abc\""));
    }

    @Test
    void invalidImportPreservesPreviousImportedFile() throws Exception {
        requireImporterTools();
        Path source = directory.resolve("invalid.toml");
        Path profile = directory.resolve("profile");
        Files.createDirectories(profile);
        Path imported = profile.resolve("imported-theme.toml");
        Files.writeString(imported, "previous theme");
        Files.writeString(source, "[theme]\nname = \"unknown\"\n");
        var failure = assertThrows(HerdrThemeImporter.ImportFailure.class,
                () -> new HerdrThemeImporter(profile).importTheme(source.toString()));
        assertTrue(failure.getMessage().startsWith("Invalid theme or URL."));
        assertFalse(failure.getMessage().contains(source.toString()));
        assertEquals("previous theme", Files.readString(imported));
    }

    @Test
    void missingSourceReportsFileAccessFailureWithoutExposingItsPath() throws Exception {
        requireImporterTools();
        Path source = directory.resolve("private-missing-theme.toml");
        var failure = assertThrows(HerdrThemeImporter.ImportFailure.class,
                () -> new HerdrThemeImporter(directory.resolve("profile")).importTheme(source.toString()));
        assertTrue(failure.getMessage().startsWith("Cannot read or install"));
        assertFalse(failure.getMessage().contains("private-missing"));
    }

    @Test
    void selectingImportedThemeBeforeImportFailsInsteadOfSilentlyUsingDefault() {
        assertThrows(IOException.class, () -> HerdrCliTheme.IMPORTED.environment(directory, Map.of()));
    }

    private static void requireImporterTools() throws Exception {
        try {
            Process check = new ProcessBuilder("bash", "-c", "python3 -c 'import tomllib'")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            try {
                assumeTrue(check.waitFor(5, TimeUnit.SECONDS) && check.exitValue() == 0,
                        "Theme imports require Bash and Python 3.11+");
            } finally {
                check.destroyForcibly();
            }
        } catch (IOException unavailable) {
            assumeTrue(false, "Theme imports require Bash and Python 3.11+");
        }
    }
}
