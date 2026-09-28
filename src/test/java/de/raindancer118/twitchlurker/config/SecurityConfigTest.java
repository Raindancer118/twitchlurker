package de.raindancer118.twitchlurker.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

class SecurityConfigTest {

    private OidcUser user(String email, Boolean verified, List<String> groups) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("sub", "1");
        if (email != null) {
            claims.put("email", email);
        }
        if (verified != null) {
            claims.put("email_verified", verified);
        }
        if (groups != null) {
            claims.put("groups", groups);
        }
        return new DefaultOidcUser(List.of(), new OidcIdToken("t", Instant.now(), Instant.now().plusSeconds(60), claims));
    }

    @Test
    void allowsVerifiedEmailOrGroup() {
        assertThat(SecurityConfig.isAllowed(user("Tom@Example.org", true, null), List.of("tom@example.org"), List.of())).isTrue();
        assertThat(SecurityConfig.isAllowed(user("x@y.z", true, List.of("lurkers")), List.of(), List.of("lurkers"))).isTrue();
    }

    @Test
    void deniesUnverifiedOtherOrEmptyAllowlists() {
        assertThat(SecurityConfig.isAllowed(user("tom@example.org", false, null), List.of("tom@example.org"), List.of())).isFalse();
        assertThat(SecurityConfig.isAllowed(user("other@example.org", true, List.of("users")), List.of("tom@example.org"), List.of("lurkers"))).isFalse();
        assertThat(SecurityConfig.isAllowed(user("tom@example.org", true, List.of("lurkers")), List.of(), List.of())).isFalse();
    }
}
