package com.ticketrush.user;

import com.ticketrush.common.security.ServiceTokens;
import com.ticketrush.common.security.UserTokens;
import com.ticketrush.common.support.TestJwts;
import com.ticketrush.common.support.TestKeys;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.ticketrush.common.support.TestJwts.validUserClaims;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthFlowIT extends IntegrationTestBase {

    @Autowired
    AdminSeeder adminSeeder;

    @Test
    void registerLoginMe() throws Exception {
        String email = uniqueEmail();
        register(email, "Secret#123").andExpect(status().isCreated()).andExpect(jsonPath("$.role").value("USER"));
        String token = loginToken(email, "Secret#123");
        mvc.perform(get("/api/users/me").header(AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.email").value(email));
    }

    @Test
    void emailIsNormalizedBeforeValidationAndStorage() throws Exception {
        register(" Alice@Example.COM ", "Secret#123").andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("alice@example.com"));
        register("alice@example.com", "Other#1234").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_TAKEN"));
        assertThat(loginToken("\tALICE@example.com ", "Secret#123")).isNotBlank();
        assertThat(jdbc.queryForObject("SELECT email FROM users WHERE email LIKE '%alice@example%'", String.class))
                .isEqualTo("alice@example.com");
    }

    @Test
    void accentedEmailIsADistinctAccount() throws Exception {
        register("bjorn@example.com", "Secret#123").andExpect(status().isCreated());
        register("björn@example.com", "Other#1234").andExpect(status().isCreated());
        assertThat(loginToken("björn@example.com", "Other#1234")).isNotBlank();
        login("björn@example.com", "Secret#123").andExpect(status().isUnauthorized());   // never resolves to bjorn's row
    }

    @Test
    void whitespaceOnlyEmailIsInvalid() throws Exception {
        register("   ", "Secret#123").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.email").exists());
    }

    @Test
    void concurrentDuplicateRegistrationsYieldExactlyOneAccount() throws Exception {
        var pool = Executors.newFixedThreadPool(10);
        var start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Callable<Integer> c = () -> {
                start.await();
                return register("race@example.com", "Secret#123").andReturn().getResponse().getStatus();
            };
            futures.add(pool.submit(c));
        }
        start.countDown();
        List<Integer> results = new ArrayList<>();
        for (var f : futures) results.add(f.get());
        pool.shutdown();
        assertThat(results).containsOnly(201, 409);
        assertThat(results.stream().filter(s -> s == 201)).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE email='race@example.com'", Integer.class)).isEqualTo(1);
    }

    @ParameterizedTest
    @CsvSource({"7,400", "8,201", "72,201", "73,400"})
    void passwordCharacterBoundaries(int len, int expected) throws Exception {
        register(uniqueEmail(), "a".repeat(len)).andExpect(status().is(expected));
    }

    @Test
    void multiByteBoundary() throws Exception {
        register(uniqueEmail(), "€".repeat(24)).andExpect(status().isCreated());           // 72 bytes
        register(uniqueEmail(), "€".repeat(25)).andExpect(status().isBadRequest())          // 25 chars, 75 bytes
                .andExpect(jsonPath("$.errors.password").exists());
    }

    @Test
    void overlongPasswordAtLoginIs401Not500() throws Exception {
        login("alice-long@example.com", "€".repeat(40)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void wrongPasswordAndUnknownEmailLookIdentical() throws Exception {
        String email = uniqueEmail();
        register(email, "Secret#123").andExpect(status().isCreated());
        String a = login(email, "nope-nope").andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
        String b = login("ghost@example.com", "nope-nope").andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
        assertThat(a).isEqualTo(b).contains("INVALID_CREDENTIALS");
    }

    @Test
    void meRejectsMissingForgedAndServiceTokens() throws Exception {
        mvc.perform(get("/api/users/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/users/me").header(AUTHORIZATION, "Bearer " + TestJwts.rs256(TestKeys.rsa(), validUserClaims("1", "ADMIN"))))
                .andExpect(status().isUnauthorized());
        String serviceToken = ServiceTokens.provider("s".repeat(32), "booking-service", Clock.systemUTC()).token();
        mvc.perform(get("/api/users/me").header(AUTHORIZATION, "Bearer " + serviceToken)).andExpect(status().isUnauthorized());
    }

    @Test
    void internalPathsAreDenied() throws Exception {
        String email = uniqueEmail();
        register(email, "Secret#123");
        mvc.perform(get("/internal/anything").header(AUTHORIZATION, "Bearer " + loginToken(email, "Secret#123")))
                .andExpect(status().isForbidden());
    }

    @Test
    void issuedTokenHasContractClaims() throws Exception {
        String email = uniqueEmail();
        register(email, "Secret#123");
        var jwt = UserTokens.decoder(KEYS.pub()).decode(loginToken(email, "Secret#123"));
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("ticketrush-user-service");
        assertThat(jwt.getAudience()).containsExactly("ticketrush-api");
        assertThat(jwt.getClaimAsString("token_use")).isEqualTo("user");
        assertThat(jwt.getClaimAsString("role")).isEqualTo("USER");
        assertThat(jwt.getId()).isNotBlank();
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofHours(1));
    }

    @Test
    void adminSeededOnceWithAdminRole() throws Exception {
        String token = loginToken("admin@ticketrush.dev", "Admin#12345");
        mvc.perform(get("/api/users/me").header(AUTHORIZATION, "Bearer " + token)).andExpect(jsonPath("$.role").value("ADMIN"));
        adminSeeder.run(null);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE email='admin@ticketrush.dev'", Integer.class)).isEqualTo(1);
    }
}
