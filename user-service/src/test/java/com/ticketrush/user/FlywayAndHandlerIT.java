package com.ticketrush.user;

import com.ticketrush.common.error.ProblemDetailsHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

class FlywayAndHandlerIT extends IntegrationTestBase {

    @Autowired
    ApplicationContext context;
    @Autowired
    Environment env;

    @Test
    void flywayAppliedV1AndHibernateValidates() {
        assertThat(jdbc.queryForObject("SELECT success FROM flyway_schema_history WHERE version='1'", Boolean.class)).isTrue();
        assertThat(env.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("user_db");
    }

    @Test
    void sharedProblemDetailsHandlerIsActive() throws Exception {
        assertThat(context.getBeansOfType(ProblemDetailsHandler.class)).hasSize(1);
        register("bad-email", "Secret#123")
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void missingKeyFileFailsStartupClearly() {
        new ApplicationContextRunner()
                .withUserConfiguration(JwtKeysConfig.class)
                .withPropertyValues("ticketrush.jwt.private-key=file:/nope.pem", "ticketrush.jwt.public-key=file:/nope.pem")
                .run(ctx -> assertThat(ctx).hasFailed().getFailure().rootCause().hasMessageContaining("nope.pem"));
    }
}
