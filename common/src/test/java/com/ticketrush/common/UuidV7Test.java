package com.ticketrush.common;

import com.ticketrush.common.id.UuidV7;
import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.HashSet;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class UuidV7Test {

    @Test
    void isVersion7UniqueAndTimeOrdered() {
        var ids = IntStream.range(0, 1000).mapToObj(i -> UuidV7.next()).toList();
        assertThat(ids).allMatch(u -> u.version() == 7);
        assertThat(ids).allMatch(u -> u.variant() == 2);
        assertThat(ids).isSortedAccordingTo(Comparator.comparing(UUID::toString));
        assertThat(new HashSet<>(ids)).hasSize(1000);
    }
}
