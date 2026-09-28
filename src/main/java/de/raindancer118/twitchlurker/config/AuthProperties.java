package de.raindancer118.twitchlurker.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** mode: "password" (one user, no identity provider needed) or "oidc" (any OpenID Connect provider). */
@ConfigurationProperties("lurker.auth")
public record AuthProperties(String mode, String username, String password) {

    public AuthProperties {
        mode = mode == null || mode.isBlank() ? "password" : mode.strip().toLowerCase(java.util.Locale.ROOT);
        username = username == null || username.isBlank() ? "admin" : username.strip();
    }
}
