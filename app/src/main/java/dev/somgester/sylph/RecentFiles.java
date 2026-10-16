package dev.somgester.sylph;

import java.lang.System.Logger.Level;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

// Updated only by successful foreground opens/saves; autosave does not reorder history.
final class RecentFiles {

    private static final int LIMIT = 20;

    interface Store {
        List<Path> load();

        void save(List<Path> files) throws BackingStoreException;
    }

    private final Store store;

    private List<Path> files = List.of();

    RecentFiles() {
        this(new Store() {
            @Override
            public List<Path> load() {
                return List.of();
            }

            @Override
            public void save(List<Path> paths) { }
        });
    }

    RecentFiles(Store store) {
        this.store = store;
        try {
            files = store.load().stream().map(path -> path.toAbsolutePath().normalize()).distinct().limit(LIMIT)
                    .toList();
        } catch (SecurityException failure) {
            reportFailure(failure);
        }
    }

    List<Path> files() {
        return files;
    }

    void record(Path file) {
        Path normalized = file.toAbsolutePath().normalize();
        if (!files.isEmpty() && normalized.equals(files.getFirst())) {
            return;
        }
        List<Path> next = new ArrayList<>();
        next.add(normalized);
        files.stream().filter(path -> !path.equals(normalized)).limit(LIMIT - 1).forEach(next::add);
        files = List.copyOf(next);
        persist();
    }

    void removeUnder(Path deleted) {
        Path target = deleted.toAbsolutePath().normalize();
        List<Path> remaining = files.stream().filter(file -> !file.startsWith(target)).toList();
        if (!remaining.equals(files)) {
            files = remaining;
            persist();
        }
    }

    private void persist() {
        try {
            store.save(files);
        } catch (BackingStoreException | SecurityException failure) {
            reportFailure(failure);
        }
    }

    static Store preferencesStore() {
        return new Store() {
            private Preferences preferences() {
                return Preferences.userNodeForPackage(RecentFiles.class).node("recentFiles");
            }

            @Override
            public List<Path> load() {
                List<Path> paths = new ArrayList<>();
                Preferences preferences = preferences();
                for (int index = 0; index < LIMIT; index++) {
                    String value = preferences.get("file." + index, "");
                    if (!value.isEmpty()) {
                        try {
                            paths.add(Path.of(value));
                        } catch (InvalidPathException ignored) {
                            // A stale or malformed preference must not prevent startup.
                        }
                    }
                }
                return paths;
            }

            @Override
            public void save(List<Path> paths) throws BackingStoreException {
                Preferences preferences = preferences();
                List<String> values = paths.stream().map(Path::toString)
                        .filter(value -> value.length() <= Preferences.MAX_VALUE_LENGTH).toList();
                for (int index = 0; index < LIMIT; index++) {
                    String key = "file." + index;
                    if (index < values.size()) {
                        preferences.put(key, values.get(index));
                    } else {
                        preferences.remove(key);
                    }
                }
                preferences.flush();
            }
        };
    }

    private static void reportFailure(Exception failure) {
        System.getLogger(RecentFiles.class.getName()).log(Level.WARNING,
                "Recent files could not be persisted; history remains available for this session", failure);
    }
}
