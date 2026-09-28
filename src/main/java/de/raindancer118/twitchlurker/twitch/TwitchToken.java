package de.raindancer118.twitchlurker.twitch;

import java.time.Instant;
import java.util.List;

public record TwitchToken(String accessToken, String login, String userId, List<String> scopes, Instant obtainedAt) {

    public TwitchToken {
        scopes = scopes == null ? List.of() : List.copyOf(scopes);
    }

    public boolean canChat() {
        return scopes.contains("chat:edit") && scopes.contains("chat:read");
    }

    @Override
    public String toString() {
        return "TwitchToken[login=" + login + ", userId=" + userId + ", scopes=" + scopes + "]";
    }
}
