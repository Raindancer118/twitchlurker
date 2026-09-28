package de.raindancer118.twitchlurker.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/** Local development without Authentik: form login with one user. Never active in the container. */
@Configuration(proxyBeanMethods = false)
@Profile("dev")
public class DevSecurityConfig {

    @Bean
    SecurityFilterChain devSecurity(HttpSecurity http) throws Exception {
        WebSecurityDefaults.apply(http);
        http.formLogin(Customizer.withDefaults());
        return http.build();
    }

    @Bean
    UserDetailsService devUsers(@Value("${lurker.dev-password}") String password) {
        var encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
        return new InMemoryUserDetailsManager(User.withUsername("dev").password(encoder.encode(password)).roles("USER").build());
    }
}
