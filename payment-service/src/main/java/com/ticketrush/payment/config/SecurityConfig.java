package com.ticketrush.payment.config;

import com.ticketrush.common.security.PemKeys;
import com.ticketrush.common.security.RoleAuthenticationConverter;
import com.ticketrush.common.security.ServiceTokens;
import com.ticketrush.common.security.UserTokens;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.Resource;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/** /internal/** = service tokens only; everything else = user tokens only (spec §13). */
@Configuration
class SecurityConfig {

    @Bean
    @Order(1)
    SecurityFilterChain internal(HttpSecurity http, @Value("${ticketrush.internal-jwt-secret}") String secret) throws Exception {
        var decoder = ServiceTokens.decoder(secret);
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
        var decoder = UserTokens.decoder(PemKeys.publicKey(publicKey));
        http.csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/api/payments", "/api/payments/**").authenticated()
                        .requestMatchers("/actuator/health", "/actuator/metrics", "/actuator/metrics/**", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html", "/error").permitAll()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(o -> o.jwt(j -> j.decoder(decoder).jwtAuthenticationConverter(new RoleAuthenticationConverter())));
        return http.build();
    }
}
