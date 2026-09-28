package de.raindancer118.twitchlurker.config;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

@Configuration(proxyBeanMethods = false)
@Profile("!dev")
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    @Bean
    SecurityFilterChain security(HttpSecurity http, OAuth2UserService<OidcUserRequest, OidcUser> allowlistedOidcUsers) throws Exception {
        WebSecurityDefaults.apply(http);
        http.oauth2Login(o -> o
                        .loginPage("/oauth2/authorization/authentik")
                        .userInfoEndpoint(u -> u.oidcUserService(allowlistedOidcUsers))
                        .failureUrl("/denied.html"))
                .exceptionHandling(e -> e
                        .defaultAuthenticationEntryPointFor(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                                PathPatternRequestMatcher.pathPattern("/api/**"))
                        .defaultAuthenticationEntryPointFor(new LoginUrlAuthenticationEntryPoint("/oauth2/authorization/authentik"),
                                PathPatternRequestMatcher.pathPattern("/**")));
        return http.build();
    }

    /** Authentik authenticates; this decides who may use the bot. Empty allowlists deny everyone. */
    @Bean
    OAuth2UserService<OidcUserRequest, OidcUser> allowlistedOidcUsers(LurkerProperties props) {
        var delegate = new OidcUserService();
        if (props.allowedEmails().isEmpty() && props.allowedGroups().isEmpty()) {
            log.warn("LURKER_ALLOWED_EMAILS and LURKER_ALLOWED_GROUPS are empty: nobody can log in.");
        }
        return request -> {
            OidcUser user = delegate.loadUser(request);
            if (!isAllowed(user, props.allowedEmails(), props.allowedGroups())) {
                throw new OAuth2AuthenticationException(new OAuth2Error("access_denied", "Nicht freigeschaltet", null));
            }
            return user;
        };
    }

    static boolean isAllowed(OidcUser user, List<String> emails, List<String> groups) {
        String email = user.getEmail();
        if (email != null && Boolean.TRUE.equals(user.getEmailVerified())
                && emails.stream().anyMatch(e -> e.equalsIgnoreCase(email.toLowerCase(Locale.ROOT)))) {
            return true;
        }
        Object claim = user.getClaims().get("groups");
        if (claim instanceof Collection<?> userGroups) {
            return userGroups.stream().map(String::valueOf).anyMatch(groups::contains);
        }
        return false;
    }
}
