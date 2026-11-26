package dev.somgester.sylph;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;

enum HerdrCliTheme {
    DEFAULT("Use Herdr configuration", ""),
    CATPPUCCIN("Catppuccin", "catppuccin"),
    TOKYO_NIGHT("Tokyo Night", "tokyo-night"),
    DRACULA("Dracula", "dracula"),
    NORD("Nord", "nord"),
    GRUVBOX("Gruvbox", "gruvbox");

    private final String label;

    private final String configName;

    HerdrCliTheme(String label, String configName) {
        this.label = label;
        this.configName = configName;
    }

    static HerdrCliTheme fromPreference(String value) {
        try {
            return valueOf(value);
        } catch (IllegalArgumentException ignored) {
            return DEFAULT;
        }
    }

    Map<String, String> environment(Path profile, Map<String, String> inherited) throws IOException {
        if (this == DEFAULT) {
            return inherited;
        }
        Path configRoot = profile.resolve("config");
        Path configDirectory = configRoot.resolve("herdr");
        Files.createDirectories(configDirectory);
        Path config = configDirectory.resolve("sylph-theme.toml");
        Path temporary = Files.createTempFile(configDirectory, "sylph-theme-", ".toml");
        try {
            Files.writeString(temporary, "onboarding = false\n\n[theme]\nname = \"" + configName + "\"\n");
            Files.move(temporary, config, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
        Map<String, String> environment = new HashMap<>(inherited);
        environment.put("HERDR_CONFIG_PATH", config.toString());
        environment.put("XDG_CONFIG_HOME", configRoot.toString());
        environment.put("XDG_STATE_HOME", profile.resolve("state").toString());
        environment.remove("HERDR_SESSION");
        environment.remove("HERDR_SOCKET_PATH");
        return environment;
    }

    @Override
    public String toString() {
        return label;
    }
}
