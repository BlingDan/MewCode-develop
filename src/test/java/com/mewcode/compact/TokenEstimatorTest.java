package com.mewcode.compact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mewcode.conversation.Message;
import com.mewcode.llm.StreamEvent;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class TokenEstimatorTest {

    @Test
    void estimatesCurrentContentByCharactersWhenThereIsNoUsageAnchor() {
        var estimator = new TokenEstimator();
        var request = new ContextRequest(List.of(), List.of(), Optional.empty());
        var history = List.of(new Message("user", "abc"));

        assertEquals(1, estimator.estimate(request, history));
        assertEquals(3, estimator.requestCharacters(request, history));
    }

    @Test
    void anchorsOnAllProviderUsageDimensionsAndAddsOnlyNewCharacters() {
        var estimator = new TokenEstimator();
        var request = new ContextRequest(List.of(), List.of(), Optional.empty());
        var initialHistory = List.of(new Message("user", "abc"));
        var laterHistory = List.of(
                new Message("user", "abc"),
                new Message("assistant", "1234567"));

        estimator.recordUsage(
                new StreamEvent.Usage(
                        OptionalLong.of(10),
                        OptionalLong.of(2),
                        OptionalLong.of(3),
                        OptionalLong.of(4)),
                initialHistory,
                request);

        assertEquals(21, estimator.estimate(request, laterHistory));
    }

    @Test
    void countsSystemToolsReminderAndHistoryInRequestCharacters() {
        var emptyRequest = new ContextRequest(List.of(), List.of(), Optional.empty());
        var fullRequest = new ContextRequest(
                List.of("system"),
                List.of(Map.of("name", "ReadFile")),
                Optional.of(new Message("user", "reminder")));
        var history = List.of(new Message("user", "history"));

        var estimator = new TokenEstimator();

        assertTrue(
                estimator.requestCharacters(fullRequest, history)
                        > estimator.requestCharacters(emptyRequest, history));
    }

    @Test
    void fallsBackToCompleteApproximationAfterHistoryReplacement() {
        var estimator = new TokenEstimator();
        var request = new ContextRequest(List.of(), List.of(), Optional.empty());
        var oldHistory = List.of(new Message("user", "old"));
        var compactedHistory = List.of(new Message("assistant", "summary"));

        estimator.recordUsage(
                new StreamEvent.Usage(
                        OptionalLong.of(100),
                        OptionalLong.empty(),
                        OptionalLong.empty(),
                        OptionalLong.of(20)),
                oldHistory,
                request);
        estimator.invalidateBaseline();

        assertEquals(2, estimator.estimate(request, compactedHistory));
    }

    @Test
    void rejectsNegativeUsageDimensions() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new StreamEvent.Usage(
                        OptionalLong.of(-1),
                        OptionalLong.empty(),
                        OptionalLong.empty(),
                        OptionalLong.of(1)));
    }
}
