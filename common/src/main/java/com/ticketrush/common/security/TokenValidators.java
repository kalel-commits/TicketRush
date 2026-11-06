package com.ticketrush.common.security;

import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;

import java.time.Instant;
import java.util.Collection;
import java.util.Objects;
import java.util.function.Predicate;

/** Builds the claim-validation chain shared by user and service tokens. */
final class TokenValidators {

    private TokenValidators() {
    }

    static OAuth2TokenValidator<Jwt> of(String issuer, String audience, String tokenUse, Predicate<String> roleRule) {
        return new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(TokenClaims.CLOCK_SKEW),
                new JwtClaimValidator<Instant>(JwtClaimNames.EXP, Objects::nonNull),
                new JwtIssuerValidator(issuer),
                new JwtClaimValidator<Collection<String>>(JwtClaimNames.AUD, aud -> aud != null && aud.contains(audience)),
                new JwtClaimValidator<String>(TokenClaims.TOKEN_USE, tokenUse::equals),
                new JwtClaimValidator<String>(JwtClaimNames.SUB, sub -> sub != null && !sub.isBlank()),
                new JwtClaimValidator<String>(TokenClaims.ROLE, role -> role != null && roleRule.test(role)));
    }
}
