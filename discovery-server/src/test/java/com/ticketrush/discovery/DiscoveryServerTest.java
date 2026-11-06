package com.ticketrush.discovery;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DiscoveryServerTest {

    @LocalServerPort
    int port;

    @Test
    void servesRegistry() {
        String body = RestClient.create("http://localhost:" + port).get().uri("/eureka/apps")
                .accept(MediaType.APPLICATION_JSON).retrieve().body(String.class);
        assertThat(body).contains("\"applications\"");
    }

    @Test
    void healthIsUp() {
        String body = RestClient.create("http://localhost:" + port).get().uri("/actuator/health").retrieve().body(String.class);
        assertThat(body).contains("\"status\":\"UP\"");
    }
}
