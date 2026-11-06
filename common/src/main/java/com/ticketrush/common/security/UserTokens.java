package com.ticketrush.common.security;

import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.security.interfaces.RSAPublicKey;
import java.util.Set;

/**
 * Verification rules for user tokens (RS256). Verify-only: nothing here can sign a token.
 * Signing lives exclusively in user-service.
 */
public final class UserTokens {

    private static final Set<String> USER_ROLES = Set.of("USER", "ADMIN");

    private UserTokens() {
    }

    /** issuer + audience + token_use=user + sub/role present + expiry with 30 s skew. */
    public static OAuth2TokenValidator<Jwt> validator() {
        return TokenValidators.of(TokenClaims.USER_ISSUER, TokenClaims.USER_AUDIENCE, TokenClaims.USE_USER, USER_ROLES::contains);
    }

    /** Servlet decoder: RS256 pinned (HS256 and alg=none are rejected). Reactive apps build their own from {@link #validator()}. */
    public static JwtDecoder decoder(RSAPublicKey key) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(key).signatureAlgorithm(SignatureAlgorithm.RS256).build();
        decoder.setJwtValidator(validator());
        return decoder;
    }
}
