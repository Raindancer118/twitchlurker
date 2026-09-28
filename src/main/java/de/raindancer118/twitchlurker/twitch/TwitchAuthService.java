package de.raindancer118.twitchlurker.twitch;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Twitch device-code login with the TV client id the miner uses, so one token serves both the miner and chat.
 * Twitch requires hourly validation of stored tokens.
 */
public class TwitchAuthService {

    public static final String CLIENT_ID = "ue6666qo983tsx6so1t0vnawi233wa";
    static final String SCOPES = "channel_read chat:read chat:edit user_blocks_edit user_blocks_read user_follows_edit user_read";

    /** Published whenever the usable token appears, changes or goes away. */
    public record TokenChanged(Optional<TwitchToken> token) {}

    private record Pending(String deviceCode, String userCode, String verificationUri, Instant expiresAt,
                           Duration interval, Instant nextPollAt) {
        Pending withNextPoll(Duration newInterval, Instant now) {
            return new Pending(deviceCode, userCode, verificationUri, expiresAt, newInterval, now.plus(newInterval));
        }
    }

    private static final Logger log = LoggerFactory.getLogger(TwitchAuthService.class);

    private final RestClient http;
    private final TokenStore store;
    private final ApplicationEventPublisher events;
    private volatile TwitchToken token;
    private volatile boolean expired;
    private volatile Pending pending;
    private volatile String lastError;

    public TwitchAuthService(RestClient.Builder builder, String idBaseUrl, TokenStore store, ApplicationEventPublisher events) {
        this.http = builder.baseUrl(idBaseUrl).build();
        this.store = store;
        this.events = events;
        this.token = store.load().orElse(null);
    }

    public Optional<TwitchToken> token() {
        return expired ? Optional.empty() : Optional.ofNullable(token);
    }

    public TwitchAuthStatus status() {
        var p = pending;
        var t = token;
        if (p != null) {
            return new TwitchAuthStatus(TwitchAuthStatus.State.PENDING, null, List.of(), false,
                    p.userCode(), p.verificationUri(), p.expiresAt(), null);
        }
        if (t == null) {
            return new TwitchAuthStatus(TwitchAuthStatus.State.NONE, null, List.of(), false, null, null, null, lastError);
        }
        return new TwitchAuthStatus(expired ? TwitchAuthStatus.State.EXPIRED : TwitchAuthStatus.State.READY,
                t.login(), t.scopes(), t.canChat(), null, null, null, lastError);
    }

    public synchronized TwitchAuthStatus startDeviceLogin() {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("client_id", CLIENT_ID);
        form.add("scopes", SCOPES);
        Map<?, ?> body = http.post().uri("/oauth2/device").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form).retrieve().body(Map.class);
        var now = Instant.now();
        var interval = Duration.ofSeconds(((Number) body.get("interval")).longValue());
        pending = new Pending((String) body.get("device_code"), (String) body.get("user_code"),
                (String) body.get("verification_uri"), now.plusSeconds(((Number) body.get("expires_in")).longValue()),
                interval, now.plus(interval));
        lastError = null;
        return status();
    }

    public void cancelDeviceLogin() {
        pending = null;
    }

    /** Called by the scheduler; respects Twitch's poll interval. */
    public void pollIfDue() {
        var p = pending;
        if (p != null && !Instant.now().isBefore(p.nextPollAt())) {
            pollOnce();
        }
    }

    /** @return true when the device flow is finished (successfully or not). */
    public synchronized boolean pollOnce() {
        var p = pending;
        if (p == null) {
            return true;
        }
        if (Instant.now().isAfter(p.expiresAt())) {
            fail("The code expired. Please start again.");
            return true;
        }
        var form = new LinkedMultiValueMap<String, String>();
        form.add("client_id", CLIENT_ID);
        form.add("device_code", p.deviceCode());
        form.add("grant_type", "urn:ietf:params:oauth:grant-type:device_code");
        try {
            Map<?, ?> body = http.post().uri("/oauth2/token").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form).retrieve().body(Map.class);
            String accessToken = (String) body.get("access_token");
            var validated = validate(accessToken);
            if (validated.isEmpty()) {
                fail("Twitch did not confirm the new token.");
                return true;
            }
            pending = null;
            setToken(validated.get());
            return true;
        } catch (HttpClientErrorException e) {
            String message = e.getResponseBodyAsString();
            if (message.contains("authorization_pending")) {
                pending = p.withNextPoll(p.interval(), Instant.now());
                return false;
            }
            if (message.contains("slow_down")) {
                pending = p.withNextPoll(p.interval().plusSeconds(5), Instant.now());
                return false;
            }
            if (message.contains("expired_token")) {
                fail("The code expired. Please start again.");
            } else {
                fail("Twitch login rejected: " + message);
            }
            return true;
        } catch (RestClientException e) {
            log.warn("Device-token poll failed, retrying: {}", e.getMessage());
            pending = p.withNextPoll(p.interval(), Instant.now());
            return false;
        }
    }

    private void fail(String message) {
        pending = null;
        lastError = message;
    }

    private Optional<TwitchToken> validate(String accessToken) {
        try {
            Map<?, ?> v = http.get().uri("/oauth2/validate").header("Authorization", "OAuth " + accessToken)
                    .retrieve().body(Map.class);
            @SuppressWarnings("unchecked")
            List<String> scopes = (List<String>) v.get("scopes");
            return Optional.of(new TwitchToken(accessToken, (String) v.get("login"), String.valueOf(v.get("user_id")),
                    scopes, Instant.now()));
        } catch (HttpClientErrorException.Unauthorized e) {
            return Optional.empty();
        }
    }

    /** Hourly validation. Only a definite 401 invalidates; network trouble keeps the token. */
    public synchronized void revalidate() {
        var t = token;
        if (t == null) {
            return;
        }
        try {
            var v = validate(t.accessToken());
            if (v.isEmpty()) {
                if (!expired) {
                    expired = true;
                    lastError = "Twitch token expired or revoked. Please sign in again.";
                    events.publishEvent(new TokenChanged(Optional.empty()));
                }
            } else if (expired || !v.get().scopes().equals(t.scopes()) || !v.get().login().equals(t.login())) {
                setToken(v.get());
            }
        } catch (RestClientException e) {
            log.warn("Token validation not possible right now: {}", e.getMessage());
        }
    }

    private void setToken(TwitchToken t) {
        store.save(t);
        token = t;
        expired = false;
        lastError = null;
        events.publishEvent(new TokenChanged(Optional.of(t)));
    }

    public synchronized void logout() {
        pending = null;
        token = null;
        expired = false;
        store.delete();
        events.publishEvent(new TokenChanged(Optional.empty()));
    }
}
