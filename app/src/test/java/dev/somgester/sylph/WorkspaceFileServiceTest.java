package dev.somgester.sylph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.util.Map;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class WorkspaceFileServiceTest {

    @TempDir
    Path directory;

    private final WorkspaceFileService files = new WorkspaceFileService();

    @Test
    void createsFilesAndFoldersWithoutReplacingExistingContent() throws IOException {
        Path folder = files.create(directory, directory, "source", true);
        Path file = files.create(directory, folder, "hello.cpp", false);
        assertTrue(Files.isDirectory(folder));
        assertEquals("", Files.readString(file));
        Files.writeString(file, "keep content");
        assertThrows(FileAlreadyExistsException.class, () -> files.create(directory, folder, "hello.cpp", false));
        assertThrows(FileAlreadyExistsException.class, () -> files.create(directory, directory, "source", true));
        assertEquals("keep content", Files.readString(file));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", ".", "..", "../escape", "nested/file", "nested\\file", "/absolute", "a\0b"})
    void rejectsInvalidNamesBeforeCreatingAnything(String name) throws IOException {
        assertThrows(IOException.class, () -> files.create(directory, directory, name, false));
        try (var children = Files.list(directory)) {
            assertEquals(0, children.count());
        }
    }

    @Test
    void rejectsWorkspaceRootAndPathsOutsideWorkspace() throws IOException {
        Path root = Files.createDirectory(directory.resolve("workspace"));
        Path outside = Files.writeString(directory.resolve("outside.txt"), "keep");
        assertThrows(IOException.class, () -> files.delete(root, root));
        assertThrows(IOException.class, () -> files.delete(root, outside));
        assertThrows(IOException.class, () -> files.create(root, directory, "escape.txt", false));
        assertEquals("keep", Files.readString(outside));
        assertTrue(Files.isDirectory(root));
    }

    @Test
    void deletingFolderUnlinksSymlinksAndLeavesTheirExternalTargetsIntact() throws IOException {
        Path root = Files.createDirectory(directory.resolve("workspace"));
        Path outside = Files.createDirectory(directory.resolve("outside"));
        Path untouched = Files.writeString(outside.resolve("keep.txt"), "keep");
        Path folder = Files.createDirectory(root.resolve("remove"));
        Files.writeString(folder.resolve("inside.txt"), "delete");
        Files.createSymbolicLink(folder.resolve("linked-folder"), outside);
        Files.createSymbolicLink(folder.resolve("linked-file"), untouched);
        assertEquals(folder, files.delete(root, folder));
        assertFalse(Files.exists(folder));
        assertEquals("keep", Files.readString(untouched));
        assertTrue(Files.isDirectory(outside));
    }

    @Test
    void refusesSymlinkAncestorsButAllowsDeletingTheSelectedLinkItself() throws IOException {
        Path root = Files.createDirectory(directory.resolve("workspace"));
        Path outside = Files.createDirectory(directory.resolve("outside"));
        Path untouched = Files.writeString(outside.resolve("keep.txt"), "keep");
        Path link = Files.createSymbolicLink(root.resolve("linked-folder"), outside);
        assertThrows(IOException.class, () -> files.create(root, link, "escape.txt", false));
        assertThrows(IOException.class, () -> files.delete(root, link.resolve("keep.txt")));
        files.delete(root, link);
        assertFalse(Files.exists(link));
        assertEquals("keep", Files.readString(untouched));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void parentReplacementCannotRedirectDeletion(boolean folder) throws IOException {
        Path root = Files.createDirectory(directory.resolve("workspace"));
        Path parent = Files.createDirectory(root.resolve("selected"));
        Path outside = Files.createDirectory(directory.resolve("outside"));
        Path target = folder ? Files.createDirectory(parent.resolve("remove"))
                : Files.writeString(parent.resolve("remove"), "original");
        Path untouched = folder ? Files.createDirectory(outside.resolve("remove"))
                : Files.writeString(outside.resolve("remove"), "outside");
        if (folder) {
            Files.writeString(target.resolve("content.txt"), "original");
            Files.writeString(untouched.resolve("content.txt"), "outside");
        }
        Path moved = root.resolve("original-selected");
        try (var stream = Files.newDirectoryStream(parent)) {
            assertTrue(stream instanceof SecureDirectoryStream<?>);
            var secure = (SecureDirectoryStream<Path>) stream;
            Files.move(parent, moved);
            Files.createSymbolicLink(parent, outside);
            WorkspaceFileService.deleteEntry(secure, target.getFileName());
        }
        assertFalse(Files.exists(moved.resolve("remove")));
        assertEquals("outside", Files.readString(folder ? untouched.resolve("content.txt") : untouched));
    }

    @Test
    void refusesSymbolicLinkAncestorsAboveTheWorkspace() throws IOException {
        Path real = Files.createDirectory(directory.resolve("real"));
        Path root = Files.createDirectory(real.resolve("workspace"));
        Path file = Files.writeString(root.resolve("keep.txt"), "keep");
        Path alias = Files.createSymbolicLink(directory.resolve("alias"), real);
        assertThrows(IOException.class,
                () -> files.delete(alias.resolve("workspace"), alias.resolve("workspace/keep.txt")));
        assertEquals("keep", Files.readString(file));
    }

    @Test
    void refusesDeletionWhenFilesystemCannotProvideSecureDirectoryHandles() throws IOException {
        URI archive = URI.create("jar:" + directory.resolve("workspace.zip").toUri());
        try (var filesystem = FileSystems.newFileSystem(archive, Map.of("create", "true"))) {
            Path root = filesystem.getPath("/");
            Path file = Files.writeString(root.resolve("keep.txt"), "keep");
            IOException failure = assertThrows(IOException.class, () -> files.delete(root, file));
            assertTrue(failure.getMessage().contains("secure workspace deletion"));
            assertEquals("keep", Files.readString(file));
        }
    }

    @Test
    void cancellationBeforeMutationLeavesFilesUntouched() throws IOException {
        Path file = Files.writeString(directory.resolve("keep.txt"), "keep");
        Thread.currentThread().interrupt();
        try {
            assertThrows(CancellationException.class, () -> files.delete(directory, file));
            assertThrows(CancellationException.class, () -> files.create(directory, directory, "new.txt", false));
        } finally {
            Thread.interrupted();
        }
        assertEquals("keep", Files.readString(file));
        assertFalse(Files.exists(directory.resolve("new.txt")));
    }
}
