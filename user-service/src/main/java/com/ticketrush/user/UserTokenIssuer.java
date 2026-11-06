package com.ticketrush.user;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.ticketrush.common.security.TokenClaims;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;

import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The ONLY class in the repository that can sign user tokens (RS256, private key mounted only into user-service). */
@Component
public class UserTokenIssuer {

    private final JwtEncoder encoder;
    private final Clock clock;

    UserTokenIssuer(RSAPublicKey publicKey, RSAPrivateKey privateKey, Clock clock) {
        RSAKey jwk = new RSAKey.Builder(publicKey).privateKey(privateKey).build();
        this.encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(jwk)));
        this.clock = clock;
    }

    public String issue(User user) {
        Instant now = clock.instant();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(TokenClaims.USER_ISSUER)
                .audience(List.of(TokenClaims.USER_AUDIENCE))
                .subject(String.valueOf(user.getId()))
                .claim(TokenClaims.ROLE, user.getRole().name())
                .claim(TokenClaims.TOKEN_USE, TokenClaims.USE_USER)
                .issuedAt(now)
                .expiresAt(now.plus(TokenClaims.USER_TOKEN_TTL))
                .id(UUID.randomUUID().toString())
                .build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), claims)).getTokenValue();
    }
}
