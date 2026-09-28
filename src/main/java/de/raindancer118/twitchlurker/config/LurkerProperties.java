package de.raindancer118.twitchlurker.config;

import java.nio.file.Path;
import java.time.ZoneId;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("lurker")
public record LurkerProperties(Path dataDir, String pythonCommand, Path runnerScript, List<String> allowedEmails,
                               List<String> allowedGroups, ZoneId zone, String twitchIdBase,
                               String communityDropsUrl) {

    public LurkerProperties {
        allowedEmails = allowedEmails == null ? List.of() : allowedEmails.stream().filter(s -> !s.isBlank()).map(String::strip).toList();
        allowedGroups = allowedGroups == null ? List.of() : allowedGroups.stream().filter(s -> !s.isBlank()).map(String::strip).toList();
        zone = zone == null ? ZoneId.of("UTC") : zone;
        twitchIdBase = twitchIdBase == null ? "https://id.twitch.tv" : twitchIdBase;
    }
}
