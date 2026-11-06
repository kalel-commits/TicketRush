package com.ticketrush.user;

import com.ticketrush.common.security.RoleAuthenticationConverter;
import com.ticketrush.common.security.UserTokens;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

import java.security.interfaces.RSAPublicKey;

@Configuration
class SecurityConfig {

    @Bean
    JwtDecoder jwtDecoder(RSAPublicKey publicKey) {
        return UserTokens.decoder(publicKey);
    }

    @Bean
    SecurityFilterChain api(HttpSecurity http, JwtDecoder decoder) throws Exception {
        http.csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/internal/**").denyAll()
                        .requestMatchers(HttpMethod.POST, "/api/users/register", "/api/users/login").permitAll()
                        .requestMatchers("/actuator/health", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html", "/error").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(o -> o.jwt(j -> j.decoder(decoder).jwtAuthenticationConverter(new RoleAuthenticationConverter())));
        return http.build();
    }
}
