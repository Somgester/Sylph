package dev.somgester.sylph;

import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;

/** User-owned server configuration. Workspace files never supply executable commands. */
final class LanguageServerSettings {

    enum Server {
        PYTHON("Python (Pyright)", "pyright-langserver", "--stdio");

        final String label;

        final String executable;

        final List<String> arguments;

        Server(String label, String executable, String... arguments) {
            this.label = label;
            this.executable = executable;
            this.arguments = List.of(arguments);
        }
    }

    record Configuration(boolean enabled, String executable) {
        Configuration {
            if (executable == null || executable.isBlank() || executable.length() > 4096
                    || executable.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("Enter an executable name or absolute path.");
            }
            executable = executable.trim();
            if (!Path.of(executable).isAbsolute() && (executable.contains("/") || executable.contains("\\"))) {
                throw new IllegalArgumentException("Use a command on PATH or an absolute executable path.");
            }
        }

        List<String> command(Server server) {
            var command = new java.util.ArrayList<String>();
            command.add(executable);
            command.addAll(server.arguments);
            return List.copyOf(command);
        }
    }

    interface Store {
        Configuration load(Server server);

        void save(Server server, Configuration configuration) throws BackingStoreException;
    }

    private final Store store;

    private final EnumMap<Server, ReadOnlyObjectWrapper<Configuration>> configurations = new EnumMap<>(Server.class);

    private final ReadOnlyStringWrapper persistenceStatus = new ReadOnlyStringWrapper("");

    LanguageServerSettings(Store store) {
        this.store = store;
        for (Server server : Server.values()) {
            Configuration loaded;
            try {
                loaded = store.load(server);
            } catch (IllegalArgumentException | SecurityException error) {
                loaded = null;
            }
            configurations.put(server, new ReadOnlyObjectWrapper<>(loaded == null
                    ? new Configuration(false, server.executable) : loaded));
        }
    }

    ReadOnlyObjectProperty<Configuration> configuration(Server server) {
        return configurations.get(server).getReadOnlyProperty();
    }

    void apply(Server server, Configuration configuration) {
        // Reapplying unchanged settings intentionally retries a failed or stopped server.
        configurations.get(server).set(new Configuration(configuration.enabled(), configuration.executable()));
        try {
            store.save(server, configuration);
            persistenceStatus.set("");
        } catch (BackingStoreException | SecurityException error) {
            persistenceStatus.set("Server settings apply this session but could not be saved.");
        }
    }

    ReadOnlyStringProperty persistenceStatusProperty() {
        return persistenceStatus.getReadOnlyProperty();
    }

    static Store memoryStore() {
        return new Store() {
            private final EnumMap<Server, Configuration> values = new EnumMap<>(Server.class);

            @Override
            public Configuration load(Server server) {
                return values.get(server);
            }

            @Override
            public void save(Server server, Configuration configuration) {
                values.put(server, configuration);
            }
        };
    }

    static Store preferencesStore() {
        return new Store() {
            private Preferences node(Server server) {
                return Preferences.userNodeForPackage(LanguageServerSettings.class)
                        .node("languageServers/" + server.name());
            }

            @Override
            public Configuration load(Server server) {
                var preferences = node(server);
                return new Configuration(preferences.getBoolean("enabled", false),
                        preferences.get("executable", server.executable));
            }

            @Override
            public void save(Server server, Configuration configuration) throws BackingStoreException {
                var preferences = node(server);
                preferences.putBoolean("enabled", configuration.enabled());
                preferences.put("executable", configuration.executable());
                preferences.flush();
            }
        };
    }
}
