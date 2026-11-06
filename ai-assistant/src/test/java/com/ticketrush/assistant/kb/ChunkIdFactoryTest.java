package com.ticketrush.assistant.kb;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChunkIdFactoryTest {

    @Test
    void sameInputsGiveTheSameIdAcrossRuns() {
        assertThat(ChunkIdFactory.id("faq.md", "How do I find events?", 0))
                .isEqualTo(ChunkIdFactory.id("faq.md", "How do I find events?", 0));
    }

    @Test
    void anyDifferenceGivesADifferentId() {
        var base = ChunkIdFactory.id("faq.md", "A", 0);
        assertThat(ChunkIdFactory.id("faq.md", "B", 0)).isNotEqualTo(base);
        assertThat(ChunkIdFactory.id("faq.md", "A", 1)).isNotEqualTo(base);
        assertThat(ChunkIdFactory.id("other.md", "A", 0)).isNotEqualTo(base);
    }
}
