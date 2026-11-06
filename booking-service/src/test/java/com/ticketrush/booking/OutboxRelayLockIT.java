package com.ticketrush.booking;

import com.ticketrush.booking.outbox.OutboxRelay;
import com.ticketrush.booking.outbox.OutboxWriter;
import com.ticketrush.common.id.UuidV7;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Found by load test S1: the relay holds its transaction open during the (synchronous) Kafka send. Under REPEATABLE
 * READ its locking read also gap-locks the "unpublished" index range, so every business transaction that appends an
 * outbox row waited for Kafka (197 s of lock waits in one run). New outbox rows must never wait for the relay.
 */
class OutboxRelayLockIT extends IntegrationTestBase {

    @Autowired
    OutboxRelay relay;
    @Autowired
    OutboxWriter outbox;
    @Autowired
    TransactionTemplate tx;

    void append() {
        tx.executeWithoutResult(s -> outbox.append(UUID.randomUUID(), "booking.cancelled", Map.of("probe", true)));
    }

    @Test
    void appendingAnOutboxRowNeverWaitsForAnInFlightRelayBatch() throws Exception {
        relay.publishBatch();   // start from an empty backlog
        append();               // one unpublished row for the relay to lock
        var docker = DockerClientFactory.instance().client();
        docker.pauseContainerCmd(KAFKA.getContainerId()).exec();   // the relay's send now hangs while holding its locks
        try {
            CompletableFuture<Integer> batch = CompletableFuture.supplyAsync(() -> {
                try {
                    return relay.publishBatch();
                } catch (RuntimeException e) {
                    return -1;   // the send times out while Kafka is paused; that is expected here
                }
            });
            Thread.sleep(1_000);   // the relay is now inside its transaction, blocked on Kafka

            long start = System.nanoTime();
            append();               // a business transaction appending a new event
            long ms = (System.nanoTime() - start) / 1_000_000;

            assertThat(ms).as("outbox append waited %d ms for the relay's Kafka send", ms).isLessThan(1_000);
            docker.unpauseContainerCmd(KAFKA.getContainerId()).exec();
            batch.get();
        } finally {
            try {
                docker.unpauseContainerCmd(KAFKA.getContainerId()).exec();
            } catch (RuntimeException alreadyRunning) {
                // unpaused above
            }
        }
        // drain what this test wrote so other tests start clean
        for (int i = 0; i < 5 && relay.publishBatch() > 0; i++) { }
        assertThat(UuidV7.next()).isNotNull();
    }
}
