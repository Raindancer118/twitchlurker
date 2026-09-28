package de.raindancer118.twitchlurker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.raindancer118.twitchlurker.events.EventService;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class TwitchlurkerApplicationTests {

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("lurker.data-dir", () -> dataDir.toString());
        registry.add("lurker.allowed-emails", () -> "tom@example.org");
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    EventService events;

    @Test
    void healthIsPublicAndUp() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        assertThat(Files.exists(dataDir.resolve("twitchlurker.db"))).isTrue();
    }

    @Test
    void publicPagesGetTheirStylesWithoutLogin() throws Exception {
        for (String path : new String[] {"/bye.html", "/denied.html", "/styles.css", "/app.css", "/motion.css", "/fonts/manrope.woff2", "/icon.svg"}) {
            mvc.perform(get(path)).andExpect(status().isOk());
        }
        mvc.perform(get("/app.js")).andExpect(status().is3xxRedirection());
    }

    @Test
    void pagesRedirectToAuthentikAndApiAnswers401() throws Exception {
        mvc.perform(get("/")).andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/oauth2/authorization/authentik"));
        mvc.perform(get("/api/overview")).andExpect(status().isUnauthorized());
        mvc.perform(get("/oauth2/authorization/authentik")).andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("https://portal.tstieh.de/application/o/authorize/")));
    }

    @Test
    void overviewForLoggedInUserWithSecurityHeaders() throws Exception {
        events.record("POINTS", "papaplatte", 50L, "CLAIM");
        mvc.perform(get("/api/overview").with(oidcLogin()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy", org.hamcrest.Matchers.containsString("default-src 'self'")))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(jsonPath("$.bot.status").value("NEEDS_LOGIN"))
                .andExpect(jsonPath("$.twitch.state").value("NONE"))
                .andExpect(jsonPath("$.stats.pointsToday").value(50))
                .andExpect(jsonPath("$.feed[0].login").value("papaplatte"));
    }

    @Test
    void mutationsNeedCsrf() throws Exception {
        mvc.perform(post("/api/bot/start").with(oidcLogin())).andExpect(status().isForbidden());
        mvc.perform(post("/api/bot/start").with(oidcLogin()).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("NEEDS_LOGIN"));
    }

    @Test
    void settingsValidationAndChannelToggles() throws Exception {
        mvc.perform(put("/api/settings").with(oidcLogin()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"streamers\":[\"ok_name\",\"nope nope\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0]").value(org.hamcrest.Matchers.containsString("nope nope")));

        mvc.perform(post("/api/channels").with(oidcLogin()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"login\":\"Zarbex\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/settings").with(oidcLogin())).andExpect(jsonPath("$.streamers[0]").value("zarbex"));

        mvc.perform(post("/api/channels/..%2Fetc/raffles").with(oidcLogin()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":false}")).andExpect(status().is4xxClientError());
    }

    @Test
    void remainingEndpointsRespond() throws Exception {
        for (String path : new String[] {"/api/channels", "/api/drops", "/api/raffles", "/api/bot", "/api/twitch", "/api/me"}) {
            mvc.perform(get(path).with(oidcLogin())).andExpect(status().isOk());
        }
    }
}
