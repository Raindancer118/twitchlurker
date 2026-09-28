package de.raindancer118.twitchlurker.twitch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

class TwitchAuthServiceTest {

    @TempDir
    Path dir;

    MockRestServiceServer server;
    TwitchAuthService service;
    List<Object> published = new ArrayList<>();

    @BeforeEach
    void setUp() {
        var builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        service = new TwitchAuthService(builder, "https://id.test", new TokenStore(dir, JsonMapper.builder().build()), published::add);
    }

    private static final String VALIDATE_OK = """
            {"client_id":"ue6666qo983tsx6so1t0vnawi233wa","login":"tomlurkt","scopes":["chat:edit","chat:read","user_read"],"user_id":"4711","expires_in":0}
            """;

    @Test
    void deviceFlowHappyPath() throws Exception {
        server.expect(requestTo("https://id.test/oauth2/device")).andExpect(method(HttpMethod.POST))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("chat%3Aedit")))
                .andRespond(withSuccess("""
                        {"device_code":"dev123","expires_in":1800,"interval":5,"user_code":"ABCDEFGH","verification_uri":"https://www.twitch.tv/activate"}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://id.test/oauth2/token"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"status\":400,\"message\":\"authorization_pending\"}"));
        server.expect(requestTo("https://id.test/oauth2/token"))
                .andRespond(withSuccess("{\"access_token\":\"tok\",\"refresh_token\":\"r\",\"token_type\":\"bearer\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://id.test/oauth2/validate")).andExpect(header("Authorization", "OAuth tok"))
                .andRespond(withSuccess(VALIDATE_OK, MediaType.APPLICATION_JSON));

        var pending = service.startDeviceLogin();
        assertThat(pending.state()).isEqualTo(TwitchAuthStatus.State.PENDING);
        assertThat(pending.userCode()).isEqualTo("ABCDEFGH");

        assertThat(service.pollOnce()).isFalse();
        assertThat(service.pollOnce()).isTrue();

        var status = service.status();
        assertThat(status.state()).isEqualTo(TwitchAuthStatus.State.READY);
        assertThat(status.login()).isEqualTo("tomlurkt");
        assertThat(status.canChat()).isTrue();
        assertThat(published).hasSize(1).first().isInstanceOf(TwitchAuthService.TokenChanged.class);

        Path tokenFile = dir.resolve("twitch-token.json");
        assertThat(Files.getPosixFilePermissions(tokenFile)).containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
        assertThat(Files.readString(tokenFile)).contains("\"accessToken\"").contains("\"userId\" : \"4711\"");
        server.verify();
    }

    @Test
    void expiredDeviceCodeFails() {
        server.expect(requestTo("https://id.test/oauth2/device")).andRespond(withSuccess("""
                {"device_code":"dev123","expires_in":1800,"interval":5,"user_code":"ABCDEFGH","verification_uri":"https://www.twitch.tv/activate"}
                """, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://id.test/oauth2/token"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"status\":400,\"message\":\"expired_token\"}"));
        service.startDeviceLogin();
        assertThat(service.pollOnce()).isTrue();
        assertThat(service.status().state()).isEqualTo(TwitchAuthStatus.State.NONE);
        assertThat(service.status().error()).contains("abgelaufen");
    }

    @Test
    void revalidationMarksExpiredToken() {
        new TokenStore(dir, JsonMapper.builder().build()).save(new TwitchToken("tok", "tomlurkt", "4711", List.of("chat:read"), Instant.now()));
        var builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        service = new TwitchAuthService(builder, "https://id.test", new TokenStore(dir, JsonMapper.builder().build()), published::add);
        assertThat(service.status().state()).isEqualTo(TwitchAuthStatus.State.READY);
        assertThat(service.status().canChat()).isFalse();

        server.expect(requestTo("https://id.test/oauth2/validate"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("{\"status\":401,\"message\":\"invalid access token\"}"));
        service.revalidate();
        assertThat(service.status().state()).isEqualTo(TwitchAuthStatus.State.EXPIRED);
        assertThat(published).hasSize(1);
    }

    @Test
    void networkErrorDuringRevalidationKeepsToken() {
        new TokenStore(dir, JsonMapper.builder().build()).save(new TwitchToken("tok", "tomlurkt", "4711", List.of("chat:edit"), Instant.now()));
        var builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        service = new TwitchAuthService(builder, "https://id.test", new TokenStore(dir, JsonMapper.builder().build()), published::add);
        server.expect(requestTo("https://id.test/oauth2/validate")).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        service.revalidate();
        assertThat(service.status().state()).isEqualTo(TwitchAuthStatus.State.READY);
        assertThat(published).isEmpty();
    }

    @Test
    void logoutRemovesToken() {
        new TokenStore(dir, JsonMapper.builder().build()).save(new TwitchToken("tok", "tomlurkt", "4711", List.of(), Instant.now()));
        var builder = RestClient.builder();
        service = new TwitchAuthService(builder, "https://id.test", new TokenStore(dir, JsonMapper.builder().build()), published::add);
        service.logout();
        assertThat(service.status().state()).isEqualTo(TwitchAuthStatus.State.NONE);
        assertThat(Files.exists(dir.resolve("twitch-token.json"))).isFalse();
        assertThat(service.token()).isEmpty();
    }
}
