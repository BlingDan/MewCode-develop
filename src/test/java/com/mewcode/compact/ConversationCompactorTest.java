package com.mewcode.compact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mewcode.conversation.ContentBlock;
import com.mewcode.conversation.Message;
import com.mewcode.conversation.ToolResultBlock;
import com.mewcode.llm.StreamEvent;
import com.mewcode.testsupport.FakeLlmClient;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConversationCompactorTest {

    @TempDir
    Path tempDir;

    @Test
    void summarizesOldAssistantAndToolContentWhilePreservingUsersAndTail() {
        var history = new com.mewcode.conversation.ConversationManager();
        history.addUserMessage("original goal");
        history.addAssistantMessage("old assistant response");
        history.addUserMessage("old constraint");
        history.addMessage(new Message(
                "user",
                List.<ContentBlock>of(new ToolResultBlock(
                        "old-tool", "old tool output", false))));
        history.addUserMessage("recent user");
        history.addAssistantMessage("R".repeat(40_000));
        history.addUserMessage("recent constraint");
        history.addAssistantMessage("R".repeat(40_000));
        history.addUserMessage("last user");

        var client = new FakeLlmClient();
        client.enqueue(
                new StreamEvent.TextDelta(summary()),
                new StreamEvent.Usage(OptionalLong.of(20), OptionalLong.of(5)),
                new StreamEvent.StreamEnd("end_turn"));
        var request = new ContextRequest(List.of("system"), List.of(), Optional.empty());

        try (var externalizer = new ToolResultExternalizer(tempDir)) {
            var compactor = new ConversationCompactor(
                    client, new TokenEstimator(), externalizer);
            var result = compactor.compact(history, request);

            assertTrue(result.changed());
            assertEquals(1, client.requestCount());
            assertTrue(client.requests().getFirst().tools().isEmpty());
            assertTrue(history.getMessages().stream()
                    .anyMatch(message -> message.textContent().contains("用户目标与约束")));
            assertTrue(history.getMessages().stream()
                    .anyMatch(message -> message.textContent().contains("必须重新读取对应文件")));
            assertTrue(history.getMessages().stream()
                    .anyMatch(message -> message.textContent().equals("original goal")));
            assertTrue(history.getMessages().stream()
                    .anyMatch(message -> message.textContent().equals("old constraint")));
            assertTrue(history.getMessages().stream()
                    .anyMatch(message -> message.textContent().equals("recent user")));
            assertTrue(history.getMessages().stream()
                    .anyMatch(message -> message.textContent().equals("last user")));
            assertFalse(history.getMessages().stream()
                    .anyMatch(message -> message.textContent().equals("old assistant response")));
            assertEquals(
                    List.of("original goal", "old constraint"),
                    history.getMessages().stream()
                            .filter(message -> message.role().equals("user"))
                            .map(Message::textContent)
                            .filter(text -> text.equals("original goal")
                                    || text.equals("old constraint"))
                            .toList());
            assertFalse(history.getMessages().stream()
                    .anyMatch(message -> message.textContent().equals("old tool output")));
        }
    }

    @Test
    void reportsNoCompactionWhenHistoryHasNoOldAssistantOrToolContent() {
        var history = new com.mewcode.conversation.ConversationManager();
        history.addUserMessage("only user message");
        var client = new FakeLlmClient();

        try (var externalizer = new ToolResultExternalizer(tempDir)) {
            var result = new ConversationCompactor(
                    client, new TokenEstimator(), externalizer)
                    .compact(history, new ContextRequest(List.of(), List.of(), Optional.empty()));

            assertFalse(result.changed());
            assertEquals(0, client.requestCount());
            assertEquals("only user message", history.getMessages().getFirst().textContent());
        }
    }

    @Test
    void keepsOriginalHistoryWhenSummaryStructureIsInvalid() {
        var history = new com.mewcode.conversation.ConversationManager();
        history.addUserMessage("goal");
        history.addAssistantMessage("old answer");
        history.addUserMessage("recent");
        history.addAssistantMessage("R".repeat(40_000));
        history.addUserMessage("last");
        history.addAssistantMessage("last answer");
        history.addUserMessage("final");
        var before = history.getMessages();
        var client = new FakeLlmClient();
        client.enqueue(
                new StreamEvent.TextDelta("not a five section summary"),
                new StreamEvent.StreamEnd("end_turn"));

        try (var externalizer = new ToolResultExternalizer(tempDir)) {
            var compactor = new ConversationCompactor(
                    client, new TokenEstimator(), externalizer);

            assertThrows(
                    ContextException.class,
                    () -> compactor.compact(
                            history,
                            new ContextRequest(List.of(), List.of(), Optional.empty())));
            assertEquals(before, history.getMessages());
        }
    }

    @Test
    void passesManualFocusOnlyToTheSummaryInstruction() {
        var history = new com.mewcode.conversation.ConversationManager();
        history.addUserMessage("goal");
        history.addAssistantMessage("old answer");
        history.addUserMessage("recent");
        history.addAssistantMessage("R".repeat(40_000));
        history.addUserMessage("last");
        history.addAssistantMessage("last answer");
        history.addUserMessage("final");
        var client = new FakeLlmClient();
        client.enqueue(new StreamEvent.TextDelta(summary()), new StreamEvent.StreamEnd("end_turn"));

        try (var externalizer = new ToolResultExternalizer(tempDir)) {
            new ConversationCompactor(client, new TokenEstimator(), externalizer)
                    .compact(history, new ContextRequest(List.of(), List.of(), Optional.empty()), "数据库迁移");

            assertTrue(client.requests().getFirst().systemSegments().stream()
                    .anyMatch(segment -> segment.contains("数据库迁移")));
            assertFalse(history.getMessages().stream()
                    .anyMatch(message -> message.textContent().contains("数据库迁移")));
        }
    }

    private static String summary() {
        return """
                # 用户目标与约束
                保留用户目标。
                # 已完成工作与关键决策
                已完成旧工作。
                # 当前代码/文件状态
                当前状态已整理。
                # 未完成事项与下一步
                下一步继续。
                # 重要工具结果文件索引
                暂无外置文件。
                """;
    }
}
