package de.raindancer118.twitchlurker.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

/** Simplest self-hosting setup: one account, password from LURKER_PASSWORD, no identity provider. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "lurker.auth.mode", havingValue = "password", matchIfMissing = true)
public class PasswordSecurityConfig {

    public static String requireStrongPassword(String password) {
        if (password == null || password.isBlank()) {
            throw new IllegalStateException("LURKER_PASSWORD is not set. Set it (at least 12 characters) or use LURKER_AUTH_MODE=oidc.");
        }
        if (password.length() < 12) {
            throw new IllegalStateException("LURKER_PASSWORD must be at least 12 characters long.");
        }
        return password;
    }

    @Bean
    SecurityFilterChain passwordSecurity(HttpSecurity http) throws Exception {
        WebSecurityDefaults.apply(http, "/login.html", "/login.js");
        http.formLogin(f -> f.loginPage("/login.html").loginProcessingUrl("/login")
                        .defaultSuccessUrl("/", true).failureUrl("/login.html?error").permitAll())
                .exceptionHandling(e -> e
                        .defaultAuthenticationEntryPointFor(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                                PathPatternRequestMatcher.pathPattern("/api/**"))
                        .defaultAuthenticationEntryPointFor(new LoginUrlAuthenticationEntryPoint("/login.html"),
                                PathPatternRequestMatcher.pathPattern("/**")));
        return http.build();
    }

    @Bean
    UserDetailsService lurkerUser(AuthProperties auth) {
        var encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
        return new InMemoryUserDetailsManager(User.withUsername(auth.username())
                .password(encoder.encode(requireStrongPassword(auth.password()))).roles("USER").build());
    }
}
