package com.ticketrush.common.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Service-to-service tokens: HS256 with INTERNAL_JWT_SECRET, issuer/audience "ticketrush-internal",
 * token_use=service, role=SERVICE. Can never produce a user or ADMIN token.
 */
public final class ServiceTokens {

    private static final Duration REFRESH_MARGIN = Duration.ofSeconds(60);

    private ServiceTokens() {
    }

    public static Provider provider(String secret, String serviceId, Clock clock) {
        return new Provider(new NimbusJwtEncoder(new ImmutableSecret<>(key(secret))), serviceId, clock);
    }

    public static JwtDecoder decoder(String secret) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key(secret)).macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(TokenValidators.of(TokenClaims.SERVICE_ISSUER, TokenClaims.SERVICE_AUDIENCE,
                TokenClaims.USE_SERVICE, TokenClaims.ROLE_SERVICE::equals));
        return decoder;
    }

    private static SecretKey key(String secret) {
        byte[] bytes = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException("INTERNAL_JWT_SECRET must be at least 32 bytes");
        }
        return new SecretKeySpec(bytes, "HmacSHA256");
    }

    /** Mints and caches this service's token; re-mints when less than 60 s of validity remain. */
    public static final class Provider {
        private final JwtEncoder encoder;
        private final String serviceId;
        private final Clock clock;
        private final ReentrantLock lock = new ReentrantLock();
        private String token;
        private Instant expiresAt = Instant.MIN;

        private Provider(JwtEncoder encoder, String serviceId, Clock clock) {
            this.encoder = encoder;
            this.serviceId = serviceId;
            this.clock = clock;
        }

        public String token() {
            lock.lock();
            try {
                Instant now = clock.instant();
                if (token == null || !now.isBefore(expiresAt.minus(REFRESH_MARGIN))) {
                    expiresAt = now.plus(TokenClaims.SERVICE_TOKEN_TTL);
                    JwtClaimsSet claims = JwtClaimsSet.builder()
                            .issuer(TokenClaims.SERVICE_ISSUER)
                            .audience(List.of(TokenClaims.SERVICE_AUDIENCE))
                            .subject(serviceId)
                            .claim(TokenClaims.ROLE, TokenClaims.ROLE_SERVICE)
                            .claim(TokenClaims.TOKEN_USE, TokenClaims.USE_SERVICE)
                            .issuedAt(now)
                            .expiresAt(expiresAt)
                            .id(UUID.randomUUID().toString())
                            .build();
                    token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                            .getTokenValue();
                }
                return token;
            } finally {
                lock.unlock();
            }
        }
    }
}
