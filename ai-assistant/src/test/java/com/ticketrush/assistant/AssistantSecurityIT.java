package com.ticketrush.assistant;

import com.ticketrush.common.security.ServiceTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Spec §24.1 / §24.7: the assistant is a read-only, user-token-only service with no way to write anything. */
class AssistantSecurityIT extends AssistantTestBase {

    @Autowired
    ApplicationContext context;
    @Autowired
    Environment env;

    @Test
    void chatRequiresAUserToken() throws Exception {
        String body = "{\"message\":\"hi\"}";
        mvc.perform(post("/api/assistant/chat").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        String service = "Bearer " + ServiceTokens.provider("assistant-test-internal-secret-0123456789", "booking-service", Clock.systemUTC()).token();
        mvc.perform(post("/api/assistant/chat").header(AUTHORIZATION, service).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        int withUser = mvc.perform(post("/api/assistant/chat").header(AUTHORIZATION, userToken(7))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse().getStatus();
        assertThat(withUser).isNotIn(401, 403);
    }

    @Test
    void reindexIsAdminOnly() throws Exception {
        mvc.perform(post("/api/assistant/admin/reindex").header(AUTHORIZATION, userToken(7))).andExpect(status().isForbidden());
    }

    @Test
    void theServiceHasNoWayToWriteBusinessState() {
        assertThat(context.getBeanNamesForType(org.springframework.security.oauth2.jwt.JwtEncoder.class)).isEmpty();
        assertThat(beanTypeAbsent("javax.sql.DataSource")).isTrue();
        assertThat(beanTypeAbsent("org.springframework.kafka.core.KafkaTemplate")).isTrue();
        assertThat(env.getProperty("ticketrush.internal-jwt-secret")).isNull();
        assertThat(env.getProperty("INTERNAL_JWT_SECRET")).isNull();
    }

    @Test
    void healthIsUpWithAQdrantComponent() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/actuator/health/qdrant")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
    }

    private boolean beanTypeAbsent(String className) {
        try {
            return context.getBeanNamesForType(Class.forName(className)).length == 0;
        } catch (ClassNotFoundException notEvenOnTheClasspath) {
            return true;
        }
    }
}
