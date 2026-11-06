package com.ticketrush.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.security.Principal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitKeyResolverTest {

    private final RateLimitConfig config = new RateLimitConfig();

    private static ServerWebExchange exchange() {
        return MockServerWebExchange.from(MockServerHttpRequest.get("/api/events")
                .remoteAddress(new InetSocketAddress("10.0.0.5", 5000)).header("X-Forwarded-For", "1.2.3.4"));
    }

    @Test
    void anonymousKeyIgnoresForwardedHeader() {
        assertThat(config.defaultKeyResolver().resolve(exchange()).block()).isEqualTo("default:10.0.0.5");
    }

    @Test
    void authenticatedKeyIsSubjectWithPrefix() {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "RS256").subject("42")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        Principal p = new JwtAuthenticationToken(jwt, List.of(), "42");
        ServerWebExchange ex = exchange().mutate().principal(Mono.just(p)).build();
        assertThat(config.strictKeyResolver().resolve(ex).block()).isEqualTo("strict:42");
        assertThat(config.assistantKeyResolver().resolve(ex).block()).isEqualTo("assistant:42");
    }
}
