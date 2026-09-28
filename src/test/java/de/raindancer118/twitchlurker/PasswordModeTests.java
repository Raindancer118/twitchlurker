package de.raindancer118.twitchlurker;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.raindancer118.twitchlurker.config.PasswordSecurityConfig;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** Self-hosting without an identity provider: one user, password from the environment. */
@SpringBootTest
@AutoConfigureMockMvc
class PasswordModeTests {

    @TempDir
    static Path dataDir;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("lurker.data-dir", () -> dataDir.toString());
        registry.add("lurker.auth.mode", () -> "password");
        registry.add("lurker.auth.username", () -> "rain");
        registry.add("lurker.auth.password", () -> "correct horse battery staple");
    }

    @Autowired
    MockMvc mvc;

    @Test
    void loginPageAndRedirects() throws Exception {
        mvc.perform(get("/")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/login.html"));
        mvc.perform(get("/api/overview")).andExpect(status().isUnauthorized());
        mvc.perform(get("/login.html")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"login-form\"")));
        mvc.perform(get("/login.js")).andExpect(status().isOk());
    }

    @Test
    void correctPasswordLogsIn() throws Exception {
        mvc.perform(post("/login").with(csrf()).param("username", "rain").param("password", "correct horse battery staple"))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/"));
        mvc.perform(post("/login").with(csrf()).param("username", "rain").param("password", "wrong"))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/login.html?error"));
    }

    @Test
    void weakOrMissingPasswordsAreRefused() {
        assertThatThrownBy(() -> PasswordSecurityConfig.requireStrongPassword(""))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("LURKER_PASSWORD");
        assertThatThrownBy(() -> PasswordSecurityConfig.requireStrongPassword("short"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("12");
    }
}
