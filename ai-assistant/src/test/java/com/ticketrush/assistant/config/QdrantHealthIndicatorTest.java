package com.ticketrush.assistant.config;

import io.qdrant.client.QdrantClient;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Spec §24.9: health is DOWN when Qdrant does not answer. */
class QdrantHealthIndicatorTest {

    @Test
    void downWhenQdrantDoesNotAnswer() {
        QdrantClient qdrant = mock(QdrantClient.class);
        when(qdrant.healthCheckAsync()).thenThrow(new RuntimeException("UNAVAILABLE"));
        assertThat(new QdrantHealthIndicator(qdrant).health().getStatus().getCode()).isEqualTo("DOWN");
    }
}
