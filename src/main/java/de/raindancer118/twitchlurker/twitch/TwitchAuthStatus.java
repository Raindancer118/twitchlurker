package de.raindancer118.twitchlurker.twitch;

import java.time.Instant;
import java.util.List;

public record TwitchAuthStatus(State state, String login, List<String> scopes, boolean canChat,
                               String userCode, String verificationUri, Instant codeExpiresAt, String error) {

    public enum State { NONE, PENDING, READY, EXPIRED }
}
