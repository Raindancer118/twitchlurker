package de.raindancer118.twitchlurker.twitch;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Optional;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

public class TokenStore {

    private final Path file;
    private final JsonMapper json;

    public TokenStore(Path dataDir, JsonMapper json) {
        this.file = dataDir.resolve("twitch-token.json");
        this.json = json;
    }

    public Path file() {
        return file;
    }

    public Optional<TwitchToken> load() {
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        return Optional.of(json.readerFor(TwitchToken.class)
                .without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(file.toFile()));
    }

    public void save(TwitchToken token) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling("twitch-token.json.tmp");
            Files.deleteIfExists(tmp);
            Files.createFile(tmp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            json.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), token);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void delete() {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
