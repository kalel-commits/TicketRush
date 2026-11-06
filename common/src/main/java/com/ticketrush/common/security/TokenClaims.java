package com.ticketrush.common.security;

import java.time.Duration;

/** Token contract shared by every service (spec §13, plan 1 "Security decision"). */
public final class TokenClaims {

    public static final String USER_ISSUER = "ticketrush-user-service";
    public static final String USER_AUDIENCE = "ticketrush-api";
    public static final String SERVICE_ISSUER = "ticketrush-internal";
    public static final String SERVICE_AUDIENCE = "ticketrush-internal";
    public static final String TOKEN_USE = "token_use";
    public static final String ROLE = "role";
    public static final String USE_USER = "user";
    public static final String USE_SERVICE = "service";
    public static final String ROLE_SERVICE = "SERVICE";
    public static final Duration CLOCK_SKEW = Duration.ofSeconds(30);
    public static final Duration USER_TOKEN_TTL = Duration.ofHours(1);
    public static final Duration SERVICE_TOKEN_TTL = Duration.ofMinutes(5);

    private TokenClaims() {
    }
}
