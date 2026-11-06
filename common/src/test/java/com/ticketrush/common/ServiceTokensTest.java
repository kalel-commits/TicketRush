package com.ticketrush.common;

import com.ticketrush.common.security.ServiceTokens;
import com.ticketrush.common.security.UserTokens;
import com.ticketrush.common.support.MutableClock;
import com.ticketrush.common.support.TestJwts;
import com.ticketrush.common.support.TestKeys;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import static com.ticketrush.common.support.TestJwts.serviceClaims;
import static com.ticketrush.common.support.TestJwts.validUserClaims;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ServiceTokensTest {

    static final String SECRET = "s".repeat(32);
    private final MutableClock clock = new MutableClock(Instant.now());
    private final TestKeys keys = TestKeys.rsa();

    @Test
    void providerTokenPassesServiceDecoder() {
        var jwt = ServiceTokens.decoder(SECRET).decode(ServiceTokens.provider(SECRET, "booking-service", Clock.systemUTC()).token());
        assertThat(jwt.getSubject()).isEqualTo("booking-service");
        assertThat(jwt.getClaimAsString("role")).isEqualTo("SERVICE");
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("ticketrush-internal");   // getIssuer() expects a URL
        assertThat(jwt.getAudience()).containsExactly("ticketrush-internal");
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void cachesUntilSixtySecondsBeforeExpiry() {
        var provider = ServiceTokens.provider(SECRET, "booking-service", clock);
        String t1 = provider.token();
        clock.advance(Duration.ofMinutes(3));
        assertThat(provider.token()).isEqualTo(t1);
        clock.advance(Duration.ofSeconds(61));
        assertThat(provider.token()).isNotEqualTo(t1);
    }

    @Test
    void serviceTokenIsRejectedByUserDecoder() {
        String t = ServiceTokens.provider(SECRET, "booking-service", Clock.systemUTC()).token();
        assertThatThrownBy(() -> UserTokens.decoder(keys.pub()).decode(t)).isInstanceOf(JwtException.class);
    }

    @Test
    void userTokenIsRejectedByServiceDecoder() {
        String t = TestJwts.rs256(keys, validUserClaims("42", "ADMIN"));
        assertThatThrownBy(() -> ServiceTokens.decoder(SECRET).decode(t)).isInstanceOf(JwtException.class);
    }

    @Test
    void hs256TokenWithUserRoleIsRejectedByServiceDecoder() {
        String t = TestJwts.hs256(SECRET, serviceClaims("booking-service").claim("role", "ADMIN"));
        assertThatThrownBy(() -> ServiceTokens.decoder(SECRET).decode(t)).isInstanceOf(JwtException.class);
    }

    @Test
    void hs256TokenWithUserKindIsRejectedByServiceDecoder() {
        String t = TestJwts.hs256(SECRET, serviceClaims("booking-service").claim("token_use", "user"));
        assertThatThrownBy(() -> ServiceTokens.decoder(SECRET).decode(t)).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsShortSecret() {
        assertThatThrownBy(() -> ServiceTokens.decoder("short")).hasMessageContaining("at least 32 bytes");
        assertThatThrownBy(() -> ServiceTokens.provider("short", "x", clock)).hasMessageContaining("at least 32 bytes");
    }
}
