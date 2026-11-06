package com.ticketrush.common.support;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.ticketrush.common.security.TokenClaims;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** Test-only token factory: signs arbitrary claim sets so tests can forge every kind of bad token. */
public final class TestJwts {

    private TestJwts() {
    }

    public static JwtClaimsSet.Builder validUserClaims(String sub, String role) {
        Instant now = Instant.now();
        return JwtClaimsSet.builder()
                .issuer(TokenClaims.USER_ISSUER)
                .audience(List.of(TokenClaims.USER_AUDIENCE))
                .subject(sub)
                .claim(TokenClaims.ROLE, role)
                .claim(TokenClaims.TOKEN_USE, TokenClaims.USE_USER)
                .issuedAt(now)
                .expiresAt(now.plusSeconds(600))
                .id(UUID.randomUUID().toString());
    }

    public static JwtClaimsSet.Builder serviceClaims(String serviceId) {
        Instant now = Instant.now();
        return JwtClaimsSet.builder()
                .issuer(TokenClaims.SERVICE_ISSUER)
                .audience(List.of(TokenClaims.SERVICE_AUDIENCE))
                .subject(serviceId)
                .claim(TokenClaims.ROLE, "SERVICE")
                .claim(TokenClaims.TOKEN_USE, TokenClaims.USE_SERVICE)
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .id(UUID.randomUUID().toString());
    }

    public static String rs256(RSAPrivateKey key, RSAPublicKey pub, JwtClaimsSet.Builder claims) {
        RSAKey jwk = new RSAKey.Builder(pub).privateKey(key).build();
        var encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(jwk)));
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), claims.build()))
                .getTokenValue();
    }

    public static String rs256(TestKeys keys, JwtClaimsSet.Builder claims) {
        return rs256(keys.priv(), keys.pub(), claims);
    }

    public static String hs256(String secret, JwtClaimsSet.Builder claims) {
        var key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        var encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims.build()))
                .getTokenValue();
    }

    /** {"alg":"none"} token with an empty signature. */
    public static String unsigned(JwtClaimsSet.Builder claims) {
        var b64 = Base64.getUrlEncoder().withoutPadding();
        String header = b64.encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String payload = b64.encodeToString(toJson(claims.build().getClaims()).getBytes(StandardCharsets.UTF_8));
        return header + "." + payload + ".";
    }

    private static String toJson(Map<String, Object> claims) {
        return claims.entrySet().stream().map(e -> "\"" + e.getKey() + "\":" + jsonValue(e.getValue()))
                .collect(Collectors.joining(",", "{", "}"));
    }

    private static String jsonValue(Object v) {
        if (v instanceof Instant i) return String.valueOf(i.getEpochSecond());
        if (v instanceof List<?> l) return l.stream().map(TestJwts::jsonValue).collect(Collectors.joining(",", "[", "]"));
        return "\"" + v + "\"";
    }
}
