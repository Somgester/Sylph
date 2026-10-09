package dev.somgester.sylph;

import java.util.List;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleIntegerProperty;

final class AppSettings {

    record Values(boolean darkTheme, boolean autoSave, int delaySeconds) { }

    interface Store {

        Values load();

        void save(Values values) throws BackingStoreException;
    }

    private final BooleanProperty darkTheme = new SimpleBooleanProperty(true);

    private final BooleanProperty autoSave = new SimpleBooleanProperty(false);

    private final IntegerProperty delaySeconds = new SimpleIntegerProperty(2);

    private final ReadOnlyStringWrapper persistenceStatus = new ReadOnlyStringWrapper("");

    AppSettings(Store store) {
        Values values = store.load();
        darkTheme.set(values.darkTheme());
        autoSave.set(values.autoSave());
        delaySeconds.set(List.of(0, 1, 2, 5, 10).contains(values.delaySeconds()) ? values.delaySeconds() : 2);
        Runnable persist = () -> {
            try {
                store.save(new Values(darkTheme.get(), autoSave.get(), delaySeconds.get()));
                persistenceStatus.set("");
            } catch (BackingStoreException | SecurityException ex) {
                persistenceStatus.set("Changes apply for this session. Settings could not be saved.");
            }
        };
        darkTheme.addListener((observable, oldValue, newValue) -> persist.run());
        autoSave.addListener((observable, oldValue, newValue) -> persist.run());
        delaySeconds.addListener((observable, oldValue, newValue) -> persist.run());
    }

    static Store preferencesStore() {
        return new Store() {
            @Override
            public Values load() {
                try {
                    Preferences preferences = Preferences.userNodeForPackage(AppSettings.class);
                    return new Values(preferences.getBoolean("darkTheme", true),
                            preferences.getBoolean("autoSave", false), preferences.getInt("autoSaveDelaySeconds", 2));
                } catch (SecurityException ex) {
                    return new Values(true, false, 2);
                }
            }

            @Override
            public void save(Values values) throws BackingStoreException {
                Preferences preferences = Preferences.userNodeForPackage(AppSettings.class);
                preferences.putBoolean("darkTheme", values.darkTheme());
                preferences.putBoolean("autoSave", values.autoSave());
                preferences.putInt("autoSaveDelaySeconds", values.delaySeconds());
                preferences.flush();
            }
        };
    }

    BooleanProperty darkThemeProperty() {
        return darkTheme;
    }

    BooleanProperty autoSaveProperty() {
        return autoSave;
    }

    IntegerProperty delaySecondsProperty() {
        return delaySeconds;
    }

    ReadOnlyStringProperty persistenceStatusProperty() {
        return persistenceStatus.getReadOnlyProperty();
    }
}
