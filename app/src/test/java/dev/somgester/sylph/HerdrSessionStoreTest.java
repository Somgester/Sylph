package dev.somgester.sylph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HerdrSessionStoreTest {

    @TempDir
    Path directory;

    @Test
    void missingFileIsEmptyAndRoundTripKeepsIdsOrderPlacementAndTheme() throws Exception {
        Path file = directory.resolve(".sylph/herdr-sessions.json");
        var store = new HerdrSessionStore(file);
        assertEquals(HerdrSessionStore.State.empty(), store.load());
        var first = tab("sylph-first", "kiro-session-123", true);
        var second = tab("sylph-second", null, false);
        var state = new HerdrSessionStore.State(List.of(first, second), second.herdrSessionId());
        store.save(state);
        assertEquals(state, new HerdrSessionStore(file).load());
        if (Files.getFileAttributeView(file, PosixFileAttributeView.class) != null) {
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file));
        }
        try (var files = Files.list(file.getParent())) {
            assertEquals(List.of(file), files.toList());
        }
        store.save(HerdrSessionStore.State.empty());
        assertEquals(HerdrSessionStore.State.empty(), store.load());
    }

    @Test
    void missingWorkingDirectoryAndImportedThemeArePreservedForLaterRecovery() throws Exception {
        var saved = new HerdrSessionStore.Tab("sylph-imported", "abc-123",
                directory.resolve("missing café folder"), false, HerdrCliTheme.IMPORTED);
        var store = new HerdrSessionStore(directory.resolve("state.json"));
        store.save(new HerdrSessionStore.State(List.of(saved), saved.herdrSessionId()));
        assertEquals(saved, store.load().tabs().getFirst());
    }

    @Test
    void invalidOrNewerStateIsNeverOverwrittenWithEmptyState() throws Exception {
        Path file = directory.resolve("state.json");
        var store = new HerdrSessionStore(file);
        for (String invalid : List.of("not JSON", "{}", "[]",
                "{\"version\":2,\"tabs\":[],\"activeSessionId\":null}",
                "{\"version\":1.5,\"tabs\":[],\"activeSessionId\":null}",
                "{\"version\":\"1\",\"tabs\":[],\"activeSessionId\":null}",
                "{\"version\":1,\"tabs\":[],\"activeSessionId\":\"sylph-missing\"}",
                "{\"version\":1,\"tabs\":[],\"activeSessionId\":null,\"unknown\":true}")) {
            Files.writeString(file, invalid);
            assertThrows(IOException.class, store::load);
            assertThrows(IOException.class, () -> store.save(HerdrSessionStore.State.empty()));
            assertEquals(invalid, Files.readString(file));
        }
    }

    @Test
    void malformedTabIdsPlacementAndThemeAreRejectedWithoutLosingFile() throws Exception {
        Path file = directory.resolve("state.json");
        var store = new HerdrSessionStore(file);
        store.save(new HerdrSessionStore.State(List.of(tab("sylph-first", "kiro-first", true)), null));
        String valid = Files.readString(file);
        for (String invalid : List.of(valid.replace("kiro-first", "bad;command"),
                valid.replace("sylph-first", "../escape"), valid.replace("NORD", "UNKNOWN_THEME"),
                valid.replace("\"inEditor\":true", "\"inEditor\":\"true\""))) {
            Files.writeString(file, invalid);
            assertThrows(IOException.class, store::load);
            assertThrows(IOException.class, () -> store.save(HerdrSessionStore.State.empty()));
            assertEquals(invalid, Files.readString(file));
        }
    }

    @Test
    void oversizedAndInvalidUtf8StateArePreserved() throws Exception {
        Path file = directory.resolve("state.json");
        var store = new HerdrSessionStore(file);
        for (byte[] bytes : List.of(new byte[1024 * 1024 + 1], new byte[] {(byte) 0xff})) {
            Files.write(file, bytes);
            assertThrows(IOException.class, store::load);
            assertThrows(IOException.class, () -> store.save(HerdrSessionStore.State.empty()));
            assertEquals(bytes.length, Files.size(file));
        }
    }

    @Test
    void stateValidationRejectsDuplicateIdsUnknownActiveTabAndShellLikeIds() {
        var first = tab("sylph-first", null, true);
        assertThrows(IllegalArgumentException.class,
                () -> new HerdrSessionStore.State(List.of(first, first), null));
        assertThrows(IllegalArgumentException.class,
                () -> new HerdrSessionStore.State(List.of(first), "sylph-missing"));
        assertThrows(IllegalArgumentException.class, () -> tab("sylph-first", "--option", true));
        assertThrows(IllegalArgumentException.class, () -> tab("sylph-first", "id\ncommand", true));
        assertThrows(IllegalArgumentException.class,
                () -> new HerdrSessionStore.Tab("sylph-first", null, Path.of("relative"), true, HerdrCliTheme.DEFAULT));
        var source = new ArrayList<>(List.of(first));
        var state = new HerdrSessionStore.State(source, first.herdrSessionId());
        source.clear();
        assertEquals(List.of(first), state.tabs());
        assertThrows(UnsupportedOperationException.class, () -> state.tabs().clear());
    }

    @Test
    void symbolicLinkStateIsNotReadOrReplaced() throws Exception {
        Path target = directory.resolve("untouched.json");
        Files.writeString(target, "private original");
        Path link = directory.resolve("state.json");
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException unavailable) {
            assumeTrue(false, "Symbolic links unavailable");
        }
        var store = new HerdrSessionStore(link);
        assertThrows(IOException.class, store::load);
        assertThrows(IOException.class, () -> store.save(HerdrSessionStore.State.empty()));
        assertTrue(Files.isSymbolicLink(link));
        assertEquals("private original", Files.readString(target));
    }

    private HerdrSessionStore.Tab tab(String herdrId, String kiroId, boolean inEditor) {
        return new HerdrSessionStore.Tab(herdrId, kiroId, directory.resolve("workspace with spaces"),
                inEditor, HerdrCliTheme.NORD);
    }
}
