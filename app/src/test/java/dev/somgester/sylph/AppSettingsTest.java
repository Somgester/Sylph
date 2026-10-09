package dev.somgester.sylph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.prefs.BackingStoreException;
import org.junit.jupiter.api.Test;

class AppSettingsTest {

    static final class MemoryStore implements AppSettings.Store {

        private AppSettings.Values values;

        MemoryStore(AppSettings.Values values) {
            this.values = values;
        }

        @Override
        public AppSettings.Values load() {
            return values;
        }

        @Override
        public void save(AppSettings.Values saved) {
            values = saved;
        }
    }

    @Test
    void themeAutosaveAndDelaySurviveReloadingSettings() {
        MemoryStore store = new MemoryStore(new AppSettings.Values(true, false, 2));
        AppSettings settings = new AppSettings(store);
        settings.darkThemeProperty().set(false);
        settings.autoSaveProperty().set(true);
        settings.delaySecondsProperty().set(5);
        AppSettings reloaded = new AppSettings(store);
        assertFalse(reloaded.darkThemeProperty().get());
        assertTrue(reloaded.autoSaveProperty().get());
        assertEquals(5, reloaded.delaySecondsProperty().get());
    }

    @Test
    void immediateAutosaveChoiceSurvivesReloadingSettings() {
        MemoryStore store = new MemoryStore(new AppSettings.Values(true, true, 2));
        AppSettings settings = new AppSettings(store);
        settings.delaySecondsProperty().set(0);
        assertEquals(0, new AppSettings(store).delaySecondsProperty().get());
    }

    @Test
    void invalidSavedDelayFallsBackToTwoSeconds() {
        AppSettings settings = new AppSettings(new MemoryStore(new AppSettings.Values(true, false, -1)));
        assertEquals(2, settings.delaySecondsProperty().get());
    }

    @Test
    void failedPersistenceKeepsSettingsActiveAndReportsTheFailure() {
        AppSettings settings = new AppSettings(new AppSettings.Store() {
            @Override
            public AppSettings.Values load() {
                return new AppSettings.Values(true, false, 2);
            }

            @Override
            public void save(AppSettings.Values values) throws BackingStoreException {
                throw new BackingStoreException("Read-only store");
            }
        });
        settings.autoSaveProperty().set(true);
        assertTrue(settings.autoSaveProperty().get());
        assertTrue(settings.persistenceStatusProperty().get().contains("could not be saved"));
    }
}
