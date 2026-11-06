package com.ticketrush.user;

import com.ticketrush.common.support.TestKeys;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Shared container + keys so all ITs reuse one Spring context. */
@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "ticketrush.admin.email=admin@ticketrush.dev",
        "ticketrush.admin.password=Admin#12345"})
@AutoConfigureMockMvc
abstract class IntegrationTestBase {

    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.0")
            .withDatabaseName("user_db").withUsername("user_svc").withPassword("user-test-pw")
            .withUrlParam("connectionTimeZone", "UTC");   // same time-zone mode as production

    static final TestKeys KEYS = TestKeys.rsa();
    static final Path KEY_DIR;

    static {
        MYSQL.start();
        try {
            KEY_DIR = Files.createTempDirectory("ticketrush-keys");
            Files.writeString(KEY_DIR.resolve("priv.pem"), KEYS.privatePem());
            Files.writeString(KEY_DIR.resolve("pub.pem"), KEYS.publicPem());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void keys(DynamicPropertyRegistry r) {
        r.add("ticketrush.jwt.private-key", () -> "file:" + KEY_DIR.resolve("priv.pem").toAbsolutePath());
        r.add("ticketrush.jwt.public-key", () -> "file:" + KEY_DIR.resolve("pub.pem").toAbsolutePath());
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ObjectMapper json;

    ResultActions register(String email, String password) throws Exception {
        return mvc.perform(post("/api/users/register").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", email, "password", password))));
    }

    ResultActions login(String email, String password) throws Exception {
        return mvc.perform(post("/api/users/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", email, "password", password))));
    }

    String loginToken(String email, String password) throws Exception {
        String body = login(email, password).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("accessToken").asString();
    }

    static String uniqueEmail() {
        return "u-" + UUID.randomUUID() + "@example.com";
    }
}
