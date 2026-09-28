package de.raindancer118.twitchlurker.settings;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

public class SettingsStore {

    private final Path file;
    private final JsonMapper json;
    private volatile LurkerSettings current;

    public SettingsStore(Path dataDir, JsonMapper json) {
        this.file = dataDir.resolve("settings.json");
        this.json = json;
        this.current = load();
    }

    private LurkerSettings load() {
        if (!Files.exists(file)) {
            return LurkerSettings.defaults();
        }
        try {
            return json.readerFor(LurkerSettings.class)
                    .without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue(file.toFile());
        } catch (RuntimeException e) {
            throw new IllegalStateException("settings.json ist beschädigt: " + file, e);
        }
    }

    public LurkerSettings get() {
        return current;
    }

    public synchronized LurkerSettings save(LurkerSettings settings) {
        var errors = settings.validate();
        if (!errors.isEmpty()) {
            throw new InvalidSettingsException(errors);
        }
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling("settings.json.tmp");
            json.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), settings);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        current = settings;
        return settings;
    }
}
