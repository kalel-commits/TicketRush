package com.ticketrush.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;

/**
 * Three token buckets (strict for booking/payment writes, assistant for LLM calls, default for the rest).
 * Keys carry a bucket prefix so buckets never mix, whatever the limiter puts in its Redis key.
 * Authenticated callers are keyed by JWT subject; anonymous ones by the socket address (never X-Forwarded-For).
 */
@Configuration
public class RateLimitConfig {

    @Bean
    RedisRateLimiter strictLimiter(@Value("${ticketrush.ratelimit.strict.replenish-rate:2}") int rate,
                                   @Value("${ticketrush.ratelimit.strict.burst-capacity:5}") int burst,
                                   @Value("${ticketrush.ratelimit.strict.requested-tokens:1}") int tokens) {
        return new RedisRateLimiter(rate, burst, tokens);
    }

    @Bean
    @Primary
    RedisRateLimiter defaultLimiter(@Value("${ticketrush.ratelimit.default.replenish-rate:20}") int rate,
                                    @Value("${ticketrush.ratelimit.default.burst-capacity:40}") int burst,
                                    @Value("${ticketrush.ratelimit.default.requested-tokens:1}") int tokens) {
        return new RedisRateLimiter(rate, burst, tokens);
    }

    /** Production 1/60/6 = 10 requests per minute with a burst of 10 (replenish rate is per second). */
    @Bean
    RedisRateLimiter assistantLimiter(@Value("${ticketrush.ratelimit.assistant.replenish-rate:1}") int rate,
                                      @Value("${ticketrush.ratelimit.assistant.burst-capacity:60}") int burst,
                                      @Value("${ticketrush.ratelimit.assistant.requested-tokens:6}") int tokens) {
        return new RedisRateLimiter(rate, burst, tokens);
    }

    @Bean
    KeyResolver strictKeyResolver() {
        return ex -> clientKey(ex).map(k -> "strict:" + k);
    }

    @Bean
    @Primary
    KeyResolver defaultKeyResolver() {
        return ex -> clientKey(ex).map(k -> "default:" + k);
    }

    @Bean
    KeyResolver assistantKeyResolver() {
        return ex -> clientKey(ex).map(k -> "assistant:" + k);
    }

    static Mono<String> clientKey(ServerWebExchange ex) {
        return ex.getPrincipal()
                .filter(JwtAuthenticationToken.class::isInstance)   // ignore anonymous principals
                .map(p -> p.getName())
                .switchIfEmpty(Mono.fromSupplier(() -> remoteAddress(ex)));
    }

    private static String remoteAddress(ServerWebExchange ex) {
        InetSocketAddress addr = ex.getRequest().getRemoteAddress();
        return addr == null || addr.getAddress() == null ? "unknown" : addr.getAddress().getHostAddress();
    }
}
