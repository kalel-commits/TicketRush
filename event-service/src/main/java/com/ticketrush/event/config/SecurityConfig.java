package com.ticketrush.event.config;

import com.ticketrush.common.security.PemKeys;
import com.ticketrush.common.security.RoleAuthenticationConverter;
import com.ticketrush.common.security.ServiceTokens;
import com.ticketrush.common.security.UserTokens;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Two chains, two token kinds (spec §13):
 *  /internal/**  service tokens only (HS256, INTERNAL_JWT_SECRET, ROLE_SERVICE)
 *  everything else  user tokens only (RS256 public key)
 * Decoders are built inline (not beans) so neither chain can pick up the other's decoder.
 */
@Configuration
class SecurityConfig {

    @Bean
    @Order(1)
    SecurityFilterChain internal(HttpSecurity http, @Value("${ticketrush.internal-jwt-secret}") String secret) throws Exception {
        var decoder = ServiceTokens.decoder(secret);   // fails fast on a secret shorter than 32 bytes
        http.securityMatcher("/internal/**")
                .csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a.anyRequest().hasRole("SERVICE"))
                .oauth2ResourceServer(o -> o.jwt(j -> j.decoder(decoder).jwtAuthenticationConverter(new RoleAuthenticationConverter())));
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain api(HttpSecurity http, @Value("${ticketrush.jwt.public-key}") Resource publicKey) throws Exception {
        var decoder = UserTokens.decoder(PemKeys.publicKey(publicKey));   // fails fast on a missing key
        http.csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers(HttpMethod.GET, "/api/events", "/api/events/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/events").hasRole("ADMIN")
                        .requestMatchers("/actuator/health", "/actuator/metrics", "/actuator/metrics/**", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html", "/error").permitAll()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(o -> o.jwt(j -> j.decoder(decoder).jwtAuthenticationConverter(new RoleAuthenticationConverter())));
        return http.build();
    }
}
