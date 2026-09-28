package de.raindancer118.twitchlurker.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Any OpenID Connect provider (Authentik, Keycloak, Authelia, Google, ...). With only issuerUri set the endpoints are
 * discovered on first login; setting the endpoints explicitly avoids that network call.
 */
@ConfigurationProperties("lurker.oidc")
public record OidcProperties(String issuerUri, String clientId, String clientSecret, List<String> scopes,
                             String authorizationUri, String tokenUri, String userInfoUri, String jwkSetUri,
                             String userNameAttribute, String groupsClaim) {

    public OidcProperties {
        scopes = scopes == null || scopes.isEmpty() ? List.of("openid", "profile", "email") : scopes;
        userNameAttribute = blank(userNameAttribute) ? "preferred_username" : userNameAttribute;
        groupsClaim = blank(groupsClaim) ? "groups" : groupsClaim;
        issuerUri = blank(issuerUri) ? null : issuerUri.strip();
        authorizationUri = blank(authorizationUri) ? null : authorizationUri.strip();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    boolean explicitEndpoints() {
        return authorizationUri != null && !blank(tokenUri) && !blank(jwkSetUri);
    }
}
