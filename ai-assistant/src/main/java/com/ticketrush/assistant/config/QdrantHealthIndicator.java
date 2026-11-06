package com.ticketrush.assistant.config;

import io.qdrant.client.QdrantClient;
import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/** /actuator/health/qdrant: without the vector store there are no grounded answers (spec §24.9). */
@Component("qdrant")
class QdrantHealthIndicator extends AbstractHealthIndicator {

    private final QdrantClient qdrant;

    QdrantHealthIndicator(QdrantClient qdrant) {
        this.qdrant = qdrant;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) throws Exception {
        var reply = qdrant.healthCheckAsync().get(2, TimeUnit.SECONDS);
        builder.up().withDetail("version", reply.getVersion());
    }
}
