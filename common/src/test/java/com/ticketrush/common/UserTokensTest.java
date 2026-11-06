package com.ticketrush.common;

import com.ticketrush.common.security.UserTokens;
import com.ticketrush.common.support.TestJwts;
import com.ticketrush.common.support.TestKeys;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

import java.time.Instant;
import java.util.List;

import static com.ticketrush.common.support.TestJwts.validUserClaims;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserTokensTest {

    private final TestKeys keys = TestKeys.rsa();
    private final JwtDecoder decoder = UserTokens.decoder(keys.pub());

    @Test
    void acceptsWellFormedUserToken() {
        var jwt = decoder.decode(TestJwts.rs256(keys, validUserClaims("42", "ADMIN")));
        assertThat(jwt.getSubject()).isEqualTo("42");
        assertThat(jwt.getClaimAsString("role")).isEqualTo("ADMIN");
    }

    @Test
    void rejectsEveryForgedOrWrongKindToken() {
        var bad = List.of(
                TestJwts.rs256(TestKeys.rsa(), validUserClaims("42", "ADMIN")),                                   // other key
                TestJwts.rs256(keys, validUserClaims("42", "USER").issuedAt(Instant.now().minusSeconds(700))
                        .expiresAt(Instant.now().minusSeconds(120))),                                              // expired
                TestJwts.rs256(keys, validUserClaims("42", "USER").audience(List.of("other"))),                   // wrong aud
                TestJwts.rs256(keys, validUserClaims("42", "USER").issuer("evil")),                               // wrong iss
                TestJwts.rs256(keys, validUserClaims("42", "USER").claim("token_use", "service")),                // wrong kind
                TestJwts.rs256(keys, validUserClaims("42", "USER").claims(c -> c.remove("exp"))),                 // no expiry
                TestJwts.rs256(keys, validUserClaims("42", "USER").claims(c -> c.remove("role"))),                // no role
                TestJwts.hs256("x".repeat(32), validUserClaims("42", "ADMIN")),                                   // alg confusion
                TestJwts.unsigned(validUserClaims("42", "ADMIN")),                                                // alg none
                "not.a.jwt");
        for (String token : bad) {
            assertThatThrownBy(() -> decoder.decode(token)).as(token).isInstanceOf(JwtException.class);
        }
    }

    @Test
    void toleratesSmallClockSkew() {
        var jwt = decoder.decode(TestJwts.rs256(keys, validUserClaims("42", "USER")
                .issuedAt(Instant.now().minusSeconds(600)).expiresAt(Instant.now().minusSeconds(10))));
        assertThat(jwt.getSubject()).isEqualTo("42");
    }
}
