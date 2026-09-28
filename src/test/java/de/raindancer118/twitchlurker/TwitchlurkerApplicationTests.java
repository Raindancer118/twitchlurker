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
import java.util.List;
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

    @Autowired
    de.raindancer118.twitchlurker.miner.MinerMessageHandler minerMessages;

    @Autowired
    de.raindancer118.twitchlurker.settings.SettingsStore settingsStore;

    private static String streamer(String login, boolean online, boolean watching) {
        return """
                {"login":"%s","channelId":"1","online":%s,"watching":%s,"points":10,"game":"g","title":"t","viewers":1,
                 "onlineSince":null,"minutesWatched":0,"streakPending":false,"dropsEligible":false,"multiplier":false,"source":"follow"}
                """.formatted(login, online, watching);
    }

    @Test
    void channelsKeepRunnerOrderAndOverviewReportsSlots() throws Exception {
        minerMessages.handleStdout(("{\"t\":\"state\",\"user\":\"u\",\"session\":\"s\",\"streamers\":["
                + streamer("zeta", true, true) + "," + streamer("alpha", false, false) + "," + streamer("mid", true, true) + "]}")
                .replace("\n", ""));
        settingsStore.save(settingsStore.get().withSlots(java.util.Arrays.asList("mid", "alpha")));
        mvc.perform(get("/api/channels").with(oidcLogin()))
                .andExpect(jsonPath("$[0].login").value("zeta"))
                .andExpect(jsonPath("$[1].login").value("alpha"))
                .andExpect(jsonPath("$[2].login").value("mid"))
                .andExpect(jsonPath("$[2].slot").value(1))
                .andExpect(jsonPath("$[1].slot").value(2));
        mvc.perform(get("/api/overview").with(oidcLogin()))
                .andExpect(jsonPath("$.slots[0].pinned").value("mid"))
                .andExpect(jsonPath("$.slots[0].streamer.login").value("mid"))
                .andExpect(jsonPath("$.slots[1].pinned").value("alpha"))
                .andExpect(jsonPath("$.slots[1].pinnedOnline").value(false))
                .andExpect(jsonPath("$.slots[1].streamer.login").value("zeta"));
        settingsStore.save(settingsStore.get().withOrder(List.of("mid")));
        mvc.perform(get("/api/channels").with(oidcLogin()))
                .andExpect(jsonPath("$[0].login").value("mid"))
                .andExpect(jsonPath("$[0].rank").value(1))
                .andExpect(jsonPath("$[1].login").value("zeta"))
                .andExpect(jsonPath("$[2].login").value("alpha"));
        mvc.perform(put("/api/slots").with(oidcLogin()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slots\":[null,\"zeta\"]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.slots[1]").value("zeta"));
        mvc.perform(put("/api/order").with(oidcLogin()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"order\":[\"mid\",\"bad name\"]}"))
                .andExpect(status().isBadRequest());
        settingsStore.save(settingsStore.get().withSlots(null).withOrder(null));
    }

    @Test
    void healthIsPublicAndUp() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        assertThat(Files.exists(dataDir.resolve("twitchlurker.db"))).isTrue();
    }

    @Test
    void publicPagesGetTheirStylesWithoutLogin() throws Exception {
        for (String path : new String[] {"/bye.html", "/denied.html", "/styles.css", "/motion.css", "/fonts/nunito.woff2", "/icon.svg"}) {
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
                .andExpect(header().string("Content-Security-Policy", org.hamcrest.Matchers.containsString("frame-src https://player.twitch.tv")))
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
