package dev.somgester.sylph;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CancellationException;

final class PaletteFileSearch {

    private static final Set<String> EXCLUDED_DIRECTORIES = Set.of(".git", ".gradle", "node_modules");

    private static final int RESULT_LIMIT = 200;

    record Result(List<Path> files, boolean limited, int unreadable) {
        Result {
            files = List.copyOf(files);
        }
    }

    private PaletteFileSearch() { }

    static boolean matches(String name, String query) {
        String normalized = name.toLowerCase(Locale.ROOT).replace('\\', '/');
        for (String word : query.strip().toLowerCase(Locale.ROOT).replace('\\', '/').split("\\s+")) {
            if (!normalized.contains(word)) {
                return false;
            }
        }
        return true;
    }

    static Result search(Path root, String query) throws IOException {
        List<Path> files = new ArrayList<>();
        int[] unreadable = {0};
        boolean[] limited = {false};
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            private void checkCancelled() {
                if (Thread.currentThread().isInterrupted()) {
                    throw new CancellationException("File search superseded");
                }
            }

            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                checkCancelled();
                return !directory.equals(root) && EXCLUDED_DIRECTORIES.contains(directory.getFileName().toString())
                        ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                checkCancelled();
                if (attributes.isRegularFile() && matches(root.relativize(file).toString(), query)) {
                    if (files.size() == RESULT_LIMIT) {
                        limited[0] = true;
                        return FileVisitResult.TERMINATE;
                    }
                    files.add(file);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException failure) throws IOException {
                checkCancelled();
                if (file.equals(root)) {
                    throw failure;
                }
                unreadable[0]++;
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException failure) {
                checkCancelled();
                if (failure != null) {
                    unreadable[0]++;
                }
                return FileVisitResult.CONTINUE;
            }
        });
        files.sort(Comparator.comparing(path -> root.relativize(path).toString(), String.CASE_INSENSITIVE_ORDER));
        return new Result(files, limited[0], unreadable[0]);
    }
}
