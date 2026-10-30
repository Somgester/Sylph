package dev.somgester.sylph;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

final class KiroSessionNames {

    private final Preferences preferences;

    KiroSessionNames() {
        preferences = Preferences.userNodeForPackage(KiroSessionNames.class).node("names");
    }

    String get(Path directory, String id) {
        try {
            return preferences.get(key(directory, id), "");
        } catch (SecurityException failure) {
            return "";
        }
    }

    void put(Path directory, String id, String name) throws BackingStoreException {
        String key = key(directory, id);
        if (name.isBlank()) {
            preferences.remove(key);
        } else {
            preferences.put(key, name);
        }
        preferences.flush();
    }

    private static String key(Path directory, String id) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((directory + "\n" + id).getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                result.append(String.format("%02x", value));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}