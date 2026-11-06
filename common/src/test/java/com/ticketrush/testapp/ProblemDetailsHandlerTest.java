package com.ticketrush.testapp;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = TestApp.class)
@AutoConfigureMockMvc
class ProblemDetailsHandlerTest {

    @Autowired
    MockMvc mvc;

    @Test
    void handlerIsRegisteredByAutoConfiguration() throws Exception {
        mvc.perform(get("/boom")).andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("SEAT_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.detail").value("max 4"));
    }

    @Test
    void apiExceptionPropertiesAreAddedToTheProblem() throws Exception {
        mvc.perform(get("/boom-with-property")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_IN_PROGRESS_OR_DONE"))
                .andExpect(jsonPath("$.paymentId").value("p-1"));
    }

    @Test
    void unexpectedErrorsDoNotLeakMessages() throws Exception {
        mvc.perform(get("/crash")).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(content().string(not(containsString("leaked"))));
    }

    @Test
    void beanValidationBecomesValidationFailedWithFieldErrors() throws Exception {
        mvc.perform(post("/validate").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.name").exists());
    }

    @Test
    void malformedJsonIs400NotInternalError() throws Exception {
        mvc.perform(post("/validate").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void typeMismatchIs400NotInternalError() throws Exception {
        mvc.perform(get("/typed/abc")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.id").exists());
    }

    @Test
    void springErrorResponsesKeepTheirStatus() throws Exception {
        mvc.perform(post("/boom")).andExpect(status().isMethodNotAllowed());
        mvc.perform(get("/no-such-path")).andExpect(status().isNotFound());
    }
}
