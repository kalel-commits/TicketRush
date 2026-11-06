package com.ticketrush.provider;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ProviderControllerTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired ProviderSettings settings;
    @Autowired ChargeStore store;

    @BeforeEach
    void reset() {
        settings.setDeclineRate(0.0);
        settings.setLatencyMs(0);
        settings.setTimeoutAfterChargeRate(0.0);
        settings.setTimeoutSleepMs(5000);
        settings.setDown(false);
        store.clear();
    }

    ResultActions charge(String key, long amount) throws Exception {
        return mvc.perform(post("/charges").header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content("{\"amountCents\":" + amount + ",\"reference\":\"booking-1\"}"));
    }

    JsonNode body(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }

    @Test
    void chargeSucceedsAndSameKeyReturnsTheStoredCharge() throws Exception {
        JsonNode first = body(charge("k1", 2500).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.amountCents").value(2500))
                .andExpect(jsonPath("$.idempotencyKey").value("k1"))
                .andExpect(jsonPath("$.refunded").value(false)));
        assertThat(first.get("chargeId").asString()).startsWith("ch_");
        JsonNode second = body(charge("k1", 2500).andExpect(status().isOk()));
        assertThat(second.get("chargeId").asString()).isEqualTo(first.get("chargeId").asString());
        mvc.perform(get("/charges")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void declineIsStoredNotReRolled() throws Exception {
        settings.setDeclineRate(1.0);
        charge("k2", 2500).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DECLINED"));
        settings.setDeclineRate(0.0);
        charge("k2", 2500).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DECLINED"));
    }

    @Test
    void lookupByIdempotencyKey() throws Exception {
        charge("k3", 100).andExpect(status().isOk());
        mvc.perform(get("/charges").param("idempotencyKey", "k3"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.idempotencyKey").value("k3"));
        mvc.perform(get("/charges").param("idempotencyKey", "nope")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CHARGE_NOT_FOUND"));   // the explicit "no such charge" answer
    }

    @Test
    void parallelChargesWithOneKeyCreateOneCharge() throws Exception {
        List<Future<String>> results = new ArrayList<>();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 20; i++) {
                results.add(pool.submit(() -> body(charge("same", 700).andExpect(status().isOk())).get("chargeId").asString()));
            }
        }
        var ids = ConcurrentHashMap.<String>newKeySet();
        for (var f : results) ids.add(f.get());
        assertThat(ids).hasSize(1);
        assertThat(store.all()).hasSize(1);
    }

    @Test
    void refundIsIdempotentAndOnlyForSucceededCharges() throws Exception {
        String chargeId = body(charge("k4", 900)).get("chargeId").asString();
        var refund = post("/refunds").header("Idempotency-Key", "refund-k4").contentType(MediaType.APPLICATION_JSON)
                .content("{\"chargeId\":\"" + chargeId + "\"}");
        JsonNode r1 = body(mvc.perform(refund).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REFUNDED")));
        JsonNode r2 = body(mvc.perform(refund).andExpect(status().isOk()));
        assertThat(r2.get("refundId").asString()).isEqualTo(r1.get("refundId").asString()).startsWith("re_");
        mvc.perform(get("/charges").param("idempotencyKey", "k4")).andExpect(jsonPath("$.refunded").value(true));

        settings.setDeclineRate(1.0);
        String declined = body(charge("k5", 900)).get("chargeId").asString();
        mvc.perform(post("/refunds").header("Idempotency-Key", "refund-k5").contentType(MediaType.APPLICATION_JSON)
                .content("{\"chargeId\":\"" + declined + "\"}")).andExpect(status().isConflict());
        mvc.perform(post("/refunds").header("Idempotency-Key", "refund-x").contentType(MediaType.APPLICATION_JSON)
                .content("{\"chargeId\":\"ch_" + UUID.randomUUID() + "\"}")).andExpect(status().isNotFound());
    }

    @Test
    void refundKeyIsBoundToOneChargeAndAChargeIsRefundedOnce() throws Exception {
        String c1 = body(charge("r1", 900)).get("chargeId").asString();
        String c2 = body(charge("r2", 900)).get("chargeId").asString();
        mvc.perform(post("/refunds").header("Idempotency-Key", "refund-a").contentType(MediaType.APPLICATION_JSON)
                .content("{\"chargeId\":\"" + c1 + "\"}")).andExpect(status().isOk());
        // same key, different charge: a client bug, not a replay
        mvc.perform(post("/refunds").header("Idempotency-Key", "refund-a").contentType(MediaType.APPLICATION_JSON)
                .content("{\"chargeId\":\"" + c2 + "\"}")).andExpect(status().isConflict());
        // already refunded under another key: no second refund
        mvc.perform(post("/refunds").header("Idempotency-Key", "refund-b").contentType(MediaType.APPLICATION_JSON)
                .content("{\"chargeId\":\"" + c1 + "\"}")).andExpect(status().isConflict());
    }

    @Test
    void downReturns503() throws Exception {
        settings.setDown(true);
        charge("k6", 100).andExpect(status().isServiceUnavailable());
        assertThat(store.all()).isEmpty();
    }

    @Test
    void timeoutAfterChargeStoresTheChargeThenRespondsLate() throws Exception {
        settings.setTimeoutAfterChargeRate(1.0);
        settings.setTimeoutSleepMs(300);
        long start = System.nanoTime();
        charge("k7", 100).andExpect(status().isOk());
        assertThat((System.nanoTime() - start) / 1_000_000).isGreaterThanOrEqualTo(300);
        assertThat(store.byKey("k7")).isPresent();
    }

    @Test
    void missingIdempotencyKeyIs400() throws Exception {
        mvc.perform(post("/charges").contentType(MediaType.APPLICATION_JSON).content("{\"amountCents\":1,\"reference\":\"r\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/refunds").contentType(MediaType.APPLICATION_JSON).content("{\"chargeId\":\"ch_x\"}"))
                .andExpect(status().isBadRequest());
    }
}
