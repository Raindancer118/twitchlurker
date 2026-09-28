package de.raindancer118.twitchlurker.config;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.ClientRegistrations;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
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
@ConditionalOnProperty(name = "lurker.auth.mode", havingValue = "oidc")
public class OidcSecurityConfig {

    static final String REGISTRATION = "oidc";
    static final String LOGIN_URL = "/oauth2/authorization/" + REGISTRATION;
    private static final Logger log = LoggerFactory.getLogger(OidcSecurityConfig.class);

    @Bean
    SecurityFilterChain security(HttpSecurity http, OAuth2UserService<OidcUserRequest, OidcUser> allowlistedOidcUsers) throws Exception {
        WebSecurityDefaults.apply(http, "/denied.html");
        http.oauth2Login(o -> o
                        .loginPage(LOGIN_URL)
                        .userInfoEndpoint(u -> u.oidcUserService(allowlistedOidcUsers))
                        .failureUrl("/denied.html"))
                .exceptionHandling(e -> e
                        .defaultAuthenticationEntryPointFor(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                                PathPatternRequestMatcher.pathPattern("/api/**"))
                        .defaultAuthenticationEntryPointFor(new LoginUrlAuthenticationEntryPoint(LOGIN_URL),
                                PathPatternRequestMatcher.pathPattern("/**")));
        return http.build();
    }

    @Bean
    ClientRegistrationRepository oidcRegistrations(OidcProperties oidc) {
        if (oidc.clientId() == null || oidc.clientId().isBlank() || (oidc.issuerUri() == null && !oidc.explicitEndpoints())) {
            throw new IllegalStateException("LURKER_AUTH_MODE=oidc needs OIDC_CLIENT_ID and OIDC_ISSUER_URI (or explicit endpoints).");
        }
        if (oidc.explicitEndpoints()) {
            var registration = base(ClientRegistration.withRegistrationId(REGISTRATION), oidc)
                    .authorizationUri(oidc.authorizationUri()).tokenUri(oidc.tokenUri()).userInfoUri(oidc.userInfoUri())
                    .jwkSetUri(oidc.jwkSetUri()).issuerUri(oidc.issuerUri()).build();
            return id -> REGISTRATION.equals(id) ? registration : null;
        }
        return new LazyDiscovery(oidc);
    }

    private static ClientRegistration.Builder base(ClientRegistration.Builder b, OidcProperties oidc) {
        return b.registrationId(REGISTRATION).clientId(oidc.clientId()).clientSecret(oidc.clientSecret())
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .clientAuthenticationMethod(oidc.clientSecret() == null || oidc.clientSecret().isBlank()
                        ? ClientAuthenticationMethod.NONE : ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .scope(oidc.scopes()).userNameAttributeName(oidc.userNameAttribute()).clientName("Login");
    }

    /** Discovers the provider on first use instead of at startup, so the app boots while the IdP is down. */
    static final class LazyDiscovery implements ClientRegistrationRepository {
        private final OidcProperties oidc;
        private volatile ClientRegistration cached;

        LazyDiscovery(OidcProperties oidc) {
            this.oidc = oidc;
        }

        @Override
        public ClientRegistration findByRegistrationId(String id) {
            if (!REGISTRATION.equals(id)) {
                return null;
            }
            var c = cached;
            if (c == null) {
                synchronized (this) {
                    if (cached == null) {
                        cached = base(ClientRegistrations.fromIssuerLocation(oidc.issuerUri()), oidc).build();
                    }
                    c = cached;
                }
            }
            return c;
        }
    }

    /** The provider authenticates; this decides who may use the bot. Empty allowlists deny everyone. */
    @Bean
    OAuth2UserService<OidcUserRequest, OidcUser> allowlistedOidcUsers(LurkerProperties props, OidcProperties oidc) {
        var delegate = new OidcUserService();
        if (props.allowedEmails().isEmpty() && props.allowedGroups().isEmpty()) {
            log.warn("LURKER_ALLOWED_EMAILS and LURKER_ALLOWED_GROUPS are empty: nobody can log in.");
        }
        return request -> {
            OidcUser user = delegate.loadUser(request);
            if (!isAllowed(user, props.allowedEmails(), props.allowedGroups(), oidc.groupsClaim())) {
                throw new OAuth2AuthenticationException(new OAuth2Error("access_denied", "Nicht freigeschaltet", null));
            }
            return user;
        };
    }

    static boolean isAllowed(OidcUser user, List<String> emails, List<String> groups) {
        return isAllowed(user, emails, groups, "groups");
    }

    static boolean isAllowed(OidcUser user, List<String> emails, List<String> groups, String groupsClaim) {
        String email = user.getEmail();
        if (email != null && Boolean.TRUE.equals(user.getEmailVerified())
                && emails.stream().anyMatch(e -> e.equalsIgnoreCase(email.toLowerCase(Locale.ROOT)))) {
            return true;
        }
        Object claim = user.getClaims().get(groupsClaim);
        if (claim instanceof Collection<?> userGroups) {
            return userGroups.stream().map(String::valueOf).anyMatch(groups::contains);
        }
        return false;
    }
}
