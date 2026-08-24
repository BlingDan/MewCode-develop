package com.mewcode.agent;

import org.junit.jupiter.api.Test;

import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenUsageAccumulatorTest {

    @Test
    void replacesDuplicateRoundUsageAndSumsDifferentRounds() {
        var usage = new TokenUsageAccumulator();
        usage.updateRound(1, OptionalLong.of(10), OptionalLong.of(2));
        usage.updateRound(1, OptionalLong.of(10), OptionalLong.of(3));
        usage.updateRound(2, OptionalLong.of(7), OptionalLong.of(4));

        assertEquals(OptionalLong.of(17), usage.inputTokens());
        assertEquals(OptionalLong.of(7), usage.outputTokens());
    }

    @Test
    void missingRoundDimensionRemainsUnknown() {
        var usage = new TokenUsageAccumulator();
        usage.updateRound(1, OptionalLong.of(10), OptionalLong.empty());

        assertEquals(OptionalLong.of(10), usage.inputTokens());
        assertTrue(usage.outputTokens().isEmpty());
    }
}
