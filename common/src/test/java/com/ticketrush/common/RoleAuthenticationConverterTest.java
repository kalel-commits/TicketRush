package com.ticketrush.common;

import com.ticketrush.common.security.RoleAuthenticationConverter;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class RoleAuthenticationConverterTest {

    @Test
    void mapsRoleAndSubject() {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "RS256").subject("42").claim("role", "ADMIN")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        var auth = new RoleAuthenticationConverter().convert(jwt);
        assertThat(auth.getName()).isEqualTo("42");
        assertThat(auth.getAuthorities()).extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_ADMIN");
    }
}
