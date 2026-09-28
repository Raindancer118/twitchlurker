package de.raindancer118.twitchlurker.config;

import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

/** Shared authorization rules and headers for the production and dev filter chains. */
final class WebSecurityDefaults {

    static final String CSP = "default-src 'self'; script-src 'self'; style-src 'self'; font-src 'self'; "
            + "img-src 'self' data: https://static-cdn.jtvnw.net; connect-src 'self'; frame-ancestors 'none'; "
            + "base-uri 'self'; form-action 'self'; object-src 'none'";

    private WebSecurityDefaults() {}

    static void apply(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(a -> a
                        .requestMatchers("/actuator/health/**", "/actuator/health", "/denied.html", "/bye.html",
                                "/fonts/**", "/icon.svg", "/favicon.ico", "/error").permitAll()
                        .anyRequest().authenticated())
                .csrf(c -> c.spa())
                .logout(l -> l.logoutUrl("/logout").logoutSuccessUrl("/bye.html"))
                .headers(h -> h
                        .contentSecurityPolicy(csp -> csp.policyDirectives(CSP))
                        .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                        .permissionsPolicyHeader(p -> p.policy("camera=(), microphone=(), geolocation=(), payment=()"))
                        .frameOptions(f -> f.deny()));
    }
}
