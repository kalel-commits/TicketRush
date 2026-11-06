package com.ticketrush.gateway;

import com.ticketrush.common.security.PemKeys;
import com.ticketrush.common.security.RoleAuthenticationConverter;
import com.ticketrush.common.security.UserTokens;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.security.interfaces.RSAPublicKey;
import java.util.List;

/**
 * Edge authorization. The gateway holds ONLY the RSA public key: it can verify user tokens but never sign any.
 * Downstream services repeat their own checks; this is not the only line of defense.
 */
@Configuration
public class GatewaySecurityConfig {

    @Bean
    RSAPublicKey jwtPublicKey(@Value("${ticketrush.jwt.public-key}") Resource location) {
        return PemKeys.publicKey(location);
    }

    @Bean
    ReactiveJwtDecoder jwtDecoder(RSAPublicKey key) {
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withPublicKey(key)
                .signatureAlgorithm(SignatureAlgorithm.RS256).build();
        decoder.setJwtValidator(UserTokens.validator());
        return decoder;
    }

    @Bean
    SecurityWebFilterChain gatewaySecurity(ServerHttpSecurity http, ReactiveJwtDecoder decoder) {
        String[] eventPaths = {"/api/events", "/api/events/**"};
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .cors(c -> c.configurationSource(corsSource()))
                .authorizeExchange(a -> a
                        .pathMatchers("/internal/**").denyAll()
                        .pathMatchers("/actuator/health").permitAll()
                        .pathMatchers(HttpMethod.POST, "/api/users/register", "/api/users/login").permitAll()
                        .pathMatchers(HttpMethod.GET, eventPaths).permitAll()
                        .pathMatchers(HttpMethod.POST, eventPaths).hasRole("ADMIN")
                        .pathMatchers(HttpMethod.PUT, eventPaths).hasRole("ADMIN")
                        .pathMatchers(HttpMethod.PATCH, eventPaths).hasRole("ADMIN")
                        .pathMatchers(HttpMethod.DELETE, eventPaths).hasRole("ADMIN")
                        .pathMatchers("/api/assistant/admin/**").hasRole("ADMIN")
                        .pathMatchers("/api/users/**", "/api/bookings/**", "/api/payments/**", "/api/assistant/**").authenticated()
                        .anyExchange().denyAll())
                .oauth2ResourceServer(o -> o.jwt(j -> j.jwtDecoder(decoder)
                        .jwtAuthenticationConverter(new ReactiveJwtAuthenticationConverterAdapter(new RoleAuthenticationConverter()))))
                .build();
    }

    private static UrlBasedCorsConfigurationSource corsSource() {
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(List.of("http://localhost:5173"));
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key"));
        cors.setExposedHeaders(List.of("Retry-After", "X-RateLimit-Remaining", "X-RateLimit-Replenish-Rate", "X-RateLimit-Burst-Capacity"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cors);
        return source;
    }
}
