package dev.somgester.sylph;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

class EditorFileService {

    record Snapshot(Path path, String text, String lineEnding, boolean bom, String digest) { }

    Snapshot read(Path path) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        byte[] bytes = readBytes(normalized);
        String content;
        try {
            content = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException ex) {
            throw new IOException("Choose a UTF-8 text file", ex);
        }
        if (content.chars().anyMatch(character -> character < 32
                && character != '\n' && character != '\r' && character != '\t')) {
            throw new IOException("Binary files cannot be opened in the editor");
        }
        boolean bom = content.startsWith("\uFEFF");
        if (bom) {
            content = content.substring(1);
        }
        String lineEnding = content.contains("\r\n") ? "\r\n" : content.contains("\r") ? "\r" : "\n";
        return new Snapshot(normalized, content.replace("\r\n", "\n").replace('\r', '\n'),
                lineEnding, bom, digest(bytes));
    }

    Snapshot save(Snapshot original, String text, Path target, boolean overwrite) throws IOException {
        Path destination = target.toAbsolutePath().normalize();
        boolean sameFile = destination.equals(original.path());
        String expected = null;
        if (sameFile) {
            if (!original.digest().equals(digest(readBytes(destination)))) {
                throw new IOException("File changed outside Sylph. Reopen it or use Save As to preserve your edits.");
            }
            if (text.equals(original.text())) {
                return original;
            }
            expected = original.digest();
        } else if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            if (!overwrite) {
                throw new FileAlreadyExistsException(destination.toString());
            }
            expected = digest(readBytes(destination));
        }
        String content = (original.bom() ? "\uFEFF" : "") + text.replace("\n", original.lineEnding());
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        Path temporary = Files.createTempFile(destination.getParent(), ".sylph-save-", ".tmp");
        try {
            Files.write(temporary, bytes);
            if (expected != null) {
                PosixFileAttributeView permissions = Files.getFileAttributeView(
                        destination, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
                if (permissions != null) {
                    Files.setPosixFilePermissions(temporary, permissions.readAttributes().permissions());
                }
                if (!expected.equals(digest(readBytes(destination)))) {
                    throw new IOException("File changed while saving. Your edits are still in the editor.");
                }
                try {
                    Files.move(temporary, destination,
                            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException ex) {
                    throw new IOException("This filesystem does not support atomic saves. Use Save As.", ex);
                }
            } else {
                Files.move(temporary, destination);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        return new Snapshot(destination, text, original.lineEnding(), original.bom(), digest(bytes));
    }

    private static byte[] readBytes(Path path) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile()) {
            throw new IOException("Choose a regular text file; symbolic links and special files are not supported");
        }
        try (var stream = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            return stream.readAllBytes();
        }
    }

    private static String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new AssertionError("Java requires SHA-256", ex);
        }
    }
}
