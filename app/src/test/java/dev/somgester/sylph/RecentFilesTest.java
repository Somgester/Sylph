package dev.somgester.sylph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.List;
import java.util.prefs.BackingStoreException;
import org.junit.jupiter.api.Test;

class RecentFilesTest {

    private static final class MemoryStore implements RecentFiles.Store {
        private List<Path> saved = List.of();

        @Override
        public List<Path> load() {
            return saved;
        }

        @Override
        public void save(List<Path> paths) {
            saved = paths;
        }
    }

    @Test
    void deduplicatesNormalizedPathsAndRestoresNewestFirstAcrossInstances() {
        MemoryStore store = new MemoryStore();
        RecentFiles recent = new RecentFiles(store);
        Path first = Path.of("first.txt").toAbsolutePath();
        Path second = Path.of("second.txt").toAbsolutePath();
        recent.record(first);
        recent.record(second);
        recent.record(first.getParent().resolve("unused/../first.txt"));
        assertEquals(List.of(first, second), recent.files());
        assertEquals(recent.files(), new RecentFiles(store).files());
        assertThrows(UnsupportedOperationException.class, () -> recent.files().add(second));
    }

    @Test
    void limitsHistoryAndDropsOldestEntries() {
        MemoryStore store = new MemoryStore();
        RecentFiles recent = new RecentFiles(store);
        for (int index = 0; index < 25; index++) {
            recent.record(Path.of("file-" + index));
        }
        assertEquals(20, recent.files().size());
        assertEquals(Path.of("file-24").toAbsolutePath(), recent.files().getFirst());
        assertEquals(Path.of("file-5").toAbsolutePath(), recent.files().getLast());
        assertEquals(recent.files(), store.saved);
    }

    @Test
    void deletingAFolderRemovesItsFilesButKeepsSimilarlyNamedSiblings() {
        MemoryStore store = new MemoryStore();
        RecentFiles recent = new RecentFiles(store);
        Path folder = Path.of("workspace/source").toAbsolutePath();
        Path sibling = Path.of("workspace/source-extra/keep.txt").toAbsolutePath();
        recent.record(folder.resolve("one.txt"));
        recent.record(folder.resolve("nested/two.txt"));
        recent.record(sibling);
        recent.removeUnder(folder);
        assertEquals(List.of(sibling), recent.files());
        assertEquals(recent.files(), new RecentFiles(store).files());
    }

    @Test
    void persistenceFailureKeepsSessionHistoryUsable() {
        RecentFiles recent = new RecentFiles(new RecentFiles.Store() {
            @Override
            public List<Path> load() {
                return List.of();
            }

            @Override
            public void save(List<Path> paths) throws BackingStoreException {
                throw new BackingStoreException("test store unavailable");
            }
        });
        Path file = Path.of("file.txt").toAbsolutePath();
        recent.record(file);
        assertEquals(List.of(file), recent.files());
    }
}
