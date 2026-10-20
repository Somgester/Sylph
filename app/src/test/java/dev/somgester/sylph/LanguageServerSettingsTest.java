package dev.somgester.sylph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.prefs.BackingStoreException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LanguageServerSettingsTest {

    @Test
    void defaultsAreDisabledAndConfigurationSurvivesReloading() {
        var store = LanguageServerSettings.memoryStore();
        var settings = new LanguageServerSettings(store);
        var server = LanguageServerSettings.Server.PYTHON;
        assertFalse(settings.configuration(server).get().enabled());
        settings.apply(server, new LanguageServerSettings.Configuration(true, "pyright-langserver"));
        assertTrue(new LanguageServerSettings(store).configuration(server).get().enabled());
        assertEquals(List.of("pyright-langserver", "--stdio"), settings.configuration(server).get().command(server));
    }

    @Test
    void absoluteExecutablePathsWithSpacesStayOneArgument() {
        String executable = Path.of(System.getProperty("java.io.tmpdir"), "my tools", "pyright-langserver").toString();
        var configuration = new LanguageServerSettings.Configuration(true, executable);
        assertEquals(List.of(executable, "--stdio"), configuration.command(LanguageServerSettings.Server.PYTHON));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "./server", "tools/server", "server\n--execute", "server\u0000"})
    void rejectsInvalidCommandsAndWorkspaceRelativeExecutables(String value) {
        assertThrows(IllegalArgumentException.class, () -> new LanguageServerSettings.Configuration(true, value));
    }

    @Test
    void persistenceFailureKeepsCurrentConfigurationAndReportsIt() {
        var settings = new LanguageServerSettings(new LanguageServerSettings.Store() {
            @Override
            public LanguageServerSettings.Configuration load(LanguageServerSettings.Server server) {
                return null;
            }

            @Override
            public void save(LanguageServerSettings.Server server, LanguageServerSettings.Configuration configuration)
                    throws BackingStoreException {
                throw new BackingStoreException("Read-only store");
            }
        });
        settings.apply(LanguageServerSettings.Server.PYTHON, new LanguageServerSettings.Configuration(true, "pyright"));
        assertTrue(settings.configuration(LanguageServerSettings.Server.PYTHON).get().enabled());
        assertTrue(settings.persistenceStatusProperty().get().contains("could not be saved"));
    }
}
