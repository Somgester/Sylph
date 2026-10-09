package dev.somgester.sylph;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EditorFileServiceTest {

    @TempDir
    Path directory;

    private final EditorFileService files = new EditorFileService();

    @Test
    void editingPreservesUtf8BomAndWindowsLineEndings() throws IOException {
        Path path = directory.resolve("windows.txt");
        Files.writeString(path, "\uFEFFHello é\r\nSecond line\r\n");
        EditorFileService.Snapshot original = files.read(path);
        assertEquals("Hello é\nSecond line\n", original.text());
        assertTrue(original.bom());
        files.save(original, "Hello 世界\nUpdated\n", path, false);
        assertEquals("\uFEFFHello 世界\r\nUpdated\r\n", Files.readString(path));
        try (var children = Files.list(directory)) {
            assertEquals(1, children.count());
        }
    }

    @Test
    void externalChangesArePreservedWhenSaveIsRefused() throws IOException {
        Path path = directory.resolve("shared.txt");
        Files.writeString(path, "Original");
        EditorFileService.Snapshot original = files.read(path);
        Files.writeString(path, "Changed by another app");
        assertThrows(IOException.class, () -> files.save(original, "My edits", path, true));
        assertEquals("Changed by another app", Files.readString(path));
    }

    @Test
    void saveAsRequiresExplicitOverwriteAndKeepsOriginalFile() throws IOException {
        Path originalPath = directory.resolve("original.txt");
        Path otherPath = directory.resolve("other.txt");
        Files.writeString(originalPath, "Original");
        Files.writeString(otherPath, "Other");
        EditorFileService.Snapshot original = files.read(originalPath);
        assertThrows(FileAlreadyExistsException.class, () -> files.save(original, "My edits", otherPath, false));
        assertEquals("Other", Files.readString(otherPath));
        files.save(original, "My edits", otherPath, true);
        assertEquals("My edits", Files.readString(otherPath));
        assertEquals("Original", Files.readString(originalPath));
    }

    @Test
    void invalidUtf8AndBinaryDataAreRefused() throws IOException {
        Path path = directory.resolve("binary.dat");
        Files.write(path, new byte[] {(byte) 0xc3, 0x28});
        assertThrows(IOException.class, () -> files.read(path));
        Files.write(path, new byte[] {0x41, 0x00, 0x42});
        assertThrows(IOException.class, () -> files.read(path));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\u007FHello", "Hello\u007Fworld", "Hello\u007F", "\uFEFFHello\u007F\r\n"})
    void delCharactersAreRefusedWithoutChangingTheFile(String content) throws IOException {
        Path path = directory.resolve("del.txt");
        Files.writeString(path, content);
        byte[] original = Files.readAllBytes(path);

        assertThrows(IOException.class, () -> files.read(path));
        assertArrayEquals(original, Files.readAllBytes(path));
    }
}
