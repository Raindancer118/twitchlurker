package de.raindancer118.twitchlurker.twitch;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Reads reward codes (e.g. Minecraft capes) on demand. Codes are never stored or logged. */
public class RewardCodeService {

    public record RewardCode(String code, Instant expiresAt) {}

    // Persisted query of twitch.tv's own "redeem" dialog in the drops inventory.
    private static final String REWARD_CODE_MODAL_HASH = "b48d2bd6e80366684092d14c97ced7a86497246c4dab0f3d877c7a308eefdd7f";
    private static final Logger log = LoggerFactory.getLogger(RewardCodeService.class);

    private final RestClient http;
    private final Supplier<Optional<TwitchToken>> token;

    public RewardCodeService(RestClient.Builder builder, String gqlUrl, Supplier<Optional<TwitchToken>> token) {
        this.http = builder.baseUrl(gqlUrl).build();
        this.token = token;
    }

    public Optional<RewardCode> code(String campaignId, String rewardId) {
        var t = token.get();
        if (t.isEmpty()) {
            return Optional.empty();
        }
        var body = Map.of("operationName", "RewardCodeModal",
                "variables", Map.of("rewardCampaignID", campaignId, "rewardID", rewardId),
                "extensions", Map.of("persistedQuery", Map.of("version", 1, "sha256Hash", REWARD_CODE_MODAL_HASH)));
        try {
            Map<?, ?> response = http.post().contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "OAuth " + t.get().accessToken())
                    .header("Client-Id", TwitchAuthService.CLIENT_ID)
                    .body(body).retrieve().body(Map.class);
            var value = path(response, "data", "currentUser", "inventory", "rewardValue");
            if (value instanceof Map<?, ?> v && v.get("value") instanceof String code && !code.isBlank()) {
                return Optional.of(new RewardCode(code, parse(v.get("expiresAt"))));
            }
        } catch (RestClientException e) {
            log.warn("Reward code lookup failed: {}", e.getMessage());
        }
        return Optional.empty();
    }

    private static Object path(Object node, String... keys) {
        for (var key : keys) {
            if (!(node instanceof Map<?, ?> m)) {
                return null;
            }
            node = m.get(key);
        }
        return node;
    }

    private static Instant parse(Object value) {
        try {
            return value instanceof String s ? Instant.parse(s) : null;
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
