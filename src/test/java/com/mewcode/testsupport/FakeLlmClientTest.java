package com.mewcode.testsupport;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.mewcode.llm.PromptRequest;
import com.mewcode.llm.StreamEvent;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class FakeLlmClientTest {

    @Test
    void recordsStructuredRequestsAndReturnsResponsesInOrder() throws Exception {
        var client = new FakeLlmClient();
        client.enqueue(new StreamEvent.TextDelta("first"), new StreamEvent.StreamEnd("end_turn"));
        client.enqueue(new StreamEvent.TextDelta("second"), new StreamEvent.StreamEnd("end_turn"));
        var firstRequest = new PromptRequest(List.of("system"), List.of(), List.of(), Optional.empty());
        var secondRequest = new PromptRequest(List.of("system"), List.of(), List.of(), Optional.empty());

        var first = client.openStream(firstRequest).next();
        var second = client.openStream(secondRequest).next();

        assertEquals("first", ((StreamEvent.TextDelta) first).text());
        assertEquals("second", ((StreamEvent.TextDelta) second).text());
        assertEquals(List.of(firstRequest, secondRequest), client.requests());
    }
}
