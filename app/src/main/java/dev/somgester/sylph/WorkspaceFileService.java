package dev.somgester.sylph;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.attribute.BasicFileAttributeView;
import java.util.concurrent.CancellationException;

final class WorkspaceFileService {

    Path create(Path workspace, Path directory, String name, boolean folder) throws IOException {
        Path parent = validate(workspace, directory, true);
        if (name == null || name.isBlank() || name.equals(".") || name.equals("..")
                || name.contains("/") || name.contains("\\") || name.indexOf('\0') >= 0) {
            throw new IOException("Enter a single file or folder name, without path separators");
        }
        Path target;
        try {
            Path filename = Path.of(name);
            if (filename.isAbsolute() || filename.getNameCount() != 1 || !filename.equals(filename.getFileName())) {
                throw new IOException("Enter a single relative file or folder name");
            }
            target = parent.resolve(filename);
        } catch (java.nio.file.InvalidPathException failure) {
            throw new IOException("This name is not valid on this filesystem", failure);
        }
        checkCancelled();
        return folder ? Files.createDirectory(target) : Files.createFile(target);
    }

    Path delete(Path workspace, Path selected) throws IOException {
        Path target = validate(workspace, selected, false);
        Path root = workspace.toAbsolutePath().normalize();
        if (target.equals(root)) {
            throw new IOException("The opened workspace folder cannot be deleted");
        }
        try (SecureDirectoryStream<Path> parent = openDirectory(target.getParent())) {
            deleteEntry(parent, target.getFileName());
        }
        return target;
    }

    // Open each ancestor without following links, including ancestors above the workspace.
    private static SecureDirectoryStream<Path> openDirectory(Path directory) throws IOException {
        checkCancelled();
        if (directory.getParent() != null) {
            try (SecureDirectoryStream<Path> parent = openDirectory(directory.getParent())) {
                return parent.newDirectoryStream(directory.getFileName(), LinkOption.NOFOLLOW_LINKS);
            }
        }
        DirectoryStream<Path> stream = Files.newDirectoryStream(directory);
        if (stream instanceof SecureDirectoryStream<Path> secure) {
            return secure;
        }
        stream.close();
        throw new IOException("This filesystem does not support secure workspace deletion");
    }

    // Mutations stay relative to this open directory even if its path is replaced.
    static void deleteEntry(SecureDirectoryStream<Path> parent, Path name) throws IOException {
        checkCancelled();
        var attributes = parent.getFileAttributeView(name, BasicFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS).readAttributes();
        if (attributes.isDirectory()) {
            try (SecureDirectoryStream<Path> children = parent.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS)) {
                for (Path child : children) {
                    deleteEntry(children, child.getFileName());
                }
            }
            checkCancelled();
            parent.deleteDirectory(name);
        } else {
            parent.deleteFile(name);
        }
    }

    private static Path validate(Path workspace, Path selected, boolean directory) throws IOException {
        Path root = workspace.toAbsolutePath().normalize();
        Path target = selected.toAbsolutePath().normalize();
        if (!target.startsWith(root)) {
            throw new IOException("Choose a path inside the opened workspace");
        }
        Path parent = directory ? target : target.getParent();
        Path current = root;
        if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("The opened workspace is no longer a regular directory");
        }
        if (parent != null && parent.startsWith(root)) {
            for (Path part : root.relativize(parent)) {
                current = current.resolve(part);
                if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Choose a regular directory; symbolic-link folders are not supported");
                }
            }
        }
        return target;
    }

    private static void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Workspace operation cancelled");
        }
    }
}
