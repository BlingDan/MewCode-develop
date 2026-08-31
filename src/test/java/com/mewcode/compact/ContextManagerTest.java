package com.mewcode.compact;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mewcode.conversation.ConversationManager;
import com.mewcode.conversation.Message;
import com.mewcode.conversation.TextBlock;
import com.mewcode.conversation.ToolResultBlock;
import com.mewcode.conversation.ToolUseBlock;
import com.mewcode.llm.StreamEvent;
import com.mewcode.testsupport.FakeLlmClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ContextManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void doesNotCallSummaryWhenCurrentRequestIsUnderAutomaticBudget() {
        var client = new FakeLlmClient();
        var conversation = new ConversationManager();
        conversation.addUserMessage("small");

        try (var manager = new ContextManager(tempDir, client, 128_000)) {
            var preparation = manager.prepareForRequest(
                    conversation,
                    new ContextRequest(List.of(), List.of(), Optional.empty()));

            assertFalse(preparation.compacted());
            assertTrue(client.requests().isEmpty());
        }
    }

    @Test
    void manySmallToolResultsTriggerCumulativeCompactionWithoutExternalizing() {
        var client = new FakeLlmClient();
        client.enqueue(
                new StreamEvent.TextDelta(summary()),
                new StreamEvent.StreamEnd("end_turn"));
        var conversation = new ConversationManager();
        conversation.addUserMessage("goal");

        try (var manager = new ContextManager(tempDir, client, 30_000)) {
            for (int index = 0; index < 20; index++) {
                manager.commitToolTurn(
                        conversation,
                        List.of(new ToolUseBlock("tool-" + index, "Echo", java.util.Map.of())),
                        List.of(new ToolResultBlock("tool-" + index, "x".repeat(3_000), false)));
            }

            assertFalse(Files.exists(manager.sessionDirectory()));
            var preparation = manager.prepareForRequest(
                    conversation,
                    new ContextRequest(List.of(), List.of(), Optional.empty()));

            assertTrue(preparation.compacted());
            assertEquals(1, client.requestCount());
        }
    }

    @Test
    void externalizedLargeResultDoesNotTriggerCumulativeCompactionByItself() {
        var client = new FakeLlmClient();
        var conversation = new ConversationManager();
        conversation.addUserMessage("read the result");

        try (var manager = new ContextManager(tempDir, client, 128_000)) {
            manager.commitToolTurn(
                    conversation,
                    List.of(new ToolUseBlock("tool-1", "Echo", java.util.Map.of())),
                    List.of(new ToolResultBlock("tool-1", "x".repeat(80_000), false)));

            var preparation = manager.prepareForRequest(
                    conversation,
                    new ContextRequest(List.of(), List.of(), Optional.empty()));

            assertFalse(preparation.compacted());
            assertEquals(0, client.requestCount());
            assertTrue(Files.exists(manager.sessionDirectory()));
        }
    }

    @Test
    void automaticallyCompactsWhenEstimatedRequestReachesBudget() {
        var client = new FakeLlmClient();
        client.enqueue(
                new StreamEvent.TextDelta(summary()),
                new StreamEvent.Usage(OptionalLong.of(20), OptionalLong.of(5)),
                new StreamEvent.StreamEnd("end_turn"));
        var conversation = historyLargeEnoughToCompact();

        try (var manager = new ContextManager(tempDir, client, 30_000)) {
            var preparation = manager.prepareForRequest(
                    conversation,
                    new ContextRequest(List.of(), List.of(), Optional.empty()));

            assertTrue(preparation.compacted());
            assertTrue(client.requests().getFirst().tools().isEmpty());
            assertTrue(conversation.getMessages().stream()
                    .anyMatch(message -> message.textContent().contains("用户目标与约束")));
        }
    }

    @Test
    void forceCompactRunsBelowAutomaticThresholdAndCommitsExternalizedToolResult() {
        var client = new FakeLlmClient();
        client.enqueue(
                new StreamEvent.TextDelta(summary()),
                new StreamEvent.StreamEnd("end_turn"));
        var conversation = historyLargeEnoughToCompact();
        var request = new ContextRequest(List.of(), List.of(), Optional.empty());
        String largeResult = "H".repeat(2_000) + "secret".repeat(10_000) + "T".repeat(2_000);

        try (var manager = new ContextManager(tempDir, client, 128_000)) {
            var compacted = manager.forceCompact(
                    conversation, request, ContextTrigger.MANUAL);
            manager.commitToolTurn(
                    conversation,
                    List.of(new TextBlock("tool call")),
                    List.of(new ToolResultBlock("tool-1", largeResult, false)));

            assertTrue(compacted.changed());
            assertTrue(conversation.getMessages().getLast().content().getFirst()
                    instanceof ToolResultBlock);
            var result = (ToolResultBlock) conversation.getMessages().getLast().content().getFirst();
            assertTrue(result.content().contains("文件："));
            assertFalse(result.content().contains("secret".repeat(100)));
        }
    }

    @Test
    void manualCompactionUsesTheNarrowerThreeThousandTokenSafetyMargin() {
        var client = new FakeLlmClient();
        client.enqueue(
                new StreamEvent.TextDelta(summary() + "S".repeat(40_000)),
                new StreamEvent.StreamEnd("end_turn"));
        var conversation = historyLargeEnoughToCompact();

        try (var manager = new ContextManager(tempDir, client, 10_000)) {
            assertThrows(
                    ContextException.class,
                    () -> manager.forceCompact(
                            conversation,
                            new ContextRequest(List.of(), List.of(), Optional.empty()),
                            ContextTrigger.MANUAL));
        }
    }

    @Test
    void opensTheAutomaticFuseAfterThreeFailuresAndSkipsTheFourthSummaryCall() {
        var client = new FakeLlmClient();
        client.enqueue(invalidSummaryEvents());
        client.enqueue(invalidSummaryEvents());
        client.enqueue(invalidSummaryEvents());
        var conversation = historyLargeEnoughToCompact();
        var request = new ContextRequest(List.of(), List.of(), Optional.empty());

        try (var manager = new ContextManager(tempDir, client, 30_000)) {
            assertThrows(ContextException.class, () -> manager.prepareForRequest(conversation, request));
            assertThrows(ContextException.class, () -> manager.prepareForRequest(conversation, request));
            assertThrows(ContextException.class, () -> manager.prepareForRequest(conversation, request));
            assertThrows(ContextException.class, () -> manager.prepareForRequest(conversation, request));

            assertEquals(3, client.requestCount());
        }
    }

    private static ConversationManager historyLargeEnoughToCompact() {
        var conversation = new ConversationManager();
        conversation.addUserMessage("goal");
        conversation.addAssistantMessage("O".repeat(40_000));
        conversation.addUserMessage("recent request");
        conversation.addAssistantMessage("R".repeat(40_000));
        conversation.addUserMessage("recent constraint");
        conversation.addAssistantMessage("recent answer");
        conversation.addUserMessage("last");
        return conversation;
    }

    private static String summary() {
        return """
                # 用户目标与约束
                目标。
                # 已完成工作与关键决策
                工作。
                # 当前代码/文件状态
                状态。
                # 未完成事项与下一步
                下一步。
                # 重要工具结果文件索引
                文件。
        """;
    }

    private static StreamEvent[] invalidSummaryEvents() {
        return new StreamEvent[] {
            new StreamEvent.TextDelta("invalid summary"),
            new StreamEvent.StreamEnd("end_turn")
        };
    }
}
