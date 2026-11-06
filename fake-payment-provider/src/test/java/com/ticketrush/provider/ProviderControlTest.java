package com.ticketrush.provider;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Runtime behaviour switch for the live demo (plan 9): changing mode must not wipe the in-memory charges. */
@SpringBootTest
@AutoConfigureMockMvc
class ProviderControlTest {

    @Autowired MockMvc mvc;
    @Autowired ProviderSettings settings;
    @Autowired ChargeStore store;

    @BeforeEach
    void reset() {
        settings.setDeclineRate(0.0);
        settings.setTimeoutAfterChargeRate(0.0);
        settings.setLatencyMs(0);
        settings.setDown(false);
        store.clear();
    }

    private void control(String body) throws Exception {
        mvc.perform(post("/__control/behaviour").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
    }

    @Test
    void setsOnlyTheGivenKnobsAndReportsTheResult() throws Exception {
        settings.setLatencyMs(25);
        mvc.perform(post("/__control/behaviour").contentType(MediaType.APPLICATION_JSON).content("{\"declineRate\":1.0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.declineRate").value(1.0))
                .andExpect(jsonPath("$.latencyMs").value(25))   // untouched
                .andExpect(jsonPath("$.down").value(false));
        assertThat(settings.getDeclineRate()).isEqualTo(1.0);
        mvc.perform(get("/__control/behaviour")).andExpect(jsonPath("$.declineRate").value(1.0));
    }

    @Test
    void declineModeDeclinesAndSwitchingBackKeepsEarlierCharges() throws Exception {
        control("{\"declineRate\":1.0}");
        mvc.perform(post("/charges").header("Idempotency-Key", "k1").contentType(MediaType.APPLICATION_JSON)
                .content("{\"amountCents\":2500,\"reference\":\"b1\"}")).andExpect(jsonPath("$.status").value("DECLINED"));
        control("{\"declineRate\":0.0}");
        mvc.perform(get("/charges").param("idempotencyKey", "k1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DECLINED"));   // memory survives the mode change
    }

    @Test
    void controlStillWorksWhileTheProviderIsDown() throws Exception {
        control("{\"down\":true}");
        mvc.perform(get("/charges")).andExpect(status().isServiceUnavailable());
        control("{\"down\":false}");
        mvc.perform(get("/charges")).andExpect(status().isOk());
    }

    @Test
    void rejectsRatesOutsideZeroToOne() throws Exception {
        mvc.perform(post("/__control/behaviour").contentType(MediaType.APPLICATION_JSON).content("{\"declineRate\":1.5}"))
                .andExpect(status().isBadRequest());
        assertThat(settings.getDeclineRate()).isZero();
    }
}
