package com.ticketrush.assistant.config;

import com.ticketrush.common.security.PemKeys;
import com.ticketrush.common.security.RoleAuthenticationConverter;
import com.ticketrush.common.security.UserTokens;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Spec §24.7: user tokens only (verified with the RSA public key; no internal secret here, so service tokens are
 * rejected). /api/assistant/admin/** needs ADMIN; everything outside the assistant API is denied.
 */
@Configuration
class SecurityConfig {

    @Bean
    SecurityFilterChain api(HttpSecurity http, @Value("${ticketrush.jwt.public-key}") Resource publicKey) throws Exception {
        var decoder = UserTokens.decoder(PemKeys.publicKey(publicKey));
        http.csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/api/assistant/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/assistant/**").authenticated()
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/error").permitAll()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(o -> o.jwt(j -> j.decoder(decoder).jwtAuthenticationConverter(new RoleAuthenticationConverter())));
        return http.build();
    }
}
