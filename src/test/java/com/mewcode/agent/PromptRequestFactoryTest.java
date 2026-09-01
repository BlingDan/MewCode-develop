package com.mewcode.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.mewcode.compact.ContextRequest;
import com.mewcode.conversation.Message;
import com.mewcode.llm.PromptRequest;
import com.mewcode.prompt.PromptBuilder;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PromptRequestFactoryTest {

  @Test
  void reusesStableSystemSegmentsAndSchedulesReminderEveryFourRounds() {
    var factory = new PromptRequestFactory(PromptBuilder.buildBundle(Path.of("project")));
    var history = List.of(new Message("user", "fix it"));
    PromptRequest first = factory.create(AgentMode.EXECUTE, 1, false, history, List.of());
    PromptRequest second = factory.create(AgentMode.EXECUTE, 2, false, history, List.of());
    PromptRequest fifth = factory.create(AgentMode.EXECUTE, 5, false, history, List.of());

    assertEquals(first.systemSegments(), second.systemSegments());
    assertEquals(first.systemSegments(), fifth.systemSegments());
    assertTrue(first.reminder().orElseThrow().textContent().contains("This is a full reminder"));
    assertFalse(second.reminder().orElseThrow().textContent().contains("This is a full reminder"));
    assertTrue(fifth.reminder().orElseThrow().textContent().contains("This is a full reminder"));
    assertEquals(history, first.history());
    assertEquals(history, second.history());
    assertEquals(1, history.size());
  }

  @Test
  void modeSwitchCanForceTheNextReminderToBeCompleteWithoutChangingHistory() {
    var factory = new PromptRequestFactory(PromptBuilder.buildBundle(Path.of("project")));
    var history = List.of(new Message("user", "plan this"));

    PromptRequest request = factory.create(AgentMode.PLAN, 2, true, history, List.of());

    String text = request.reminder().orElseThrow().textContent();
    assertTrue(text.contains("Current mode: PLAN"));
    assertTrue(text.contains("This is a full reminder"));
    assertFalse(text.contains("plan this\n<system-reminder>"));
    assertNotEquals(request.history().size() + 1, history.size());
  }

  @Test
  void snapshotsToolDefinitionsAtFactoryBoundary() {
    var tools = List.<Map<String, Object>>of(Map.of("name", "ReadFile"));
    PromptRequest request =
        new PromptRequestFactory(PromptBuilder.buildBundle(Path.of("project")))
            .create(AgentMode.EXECUTE, 1, false, List.of(), tools);

    assertEquals(tools, request.tools());
  }

  @Test
  void createsContextRequestWithoutPersistingConversationHistory() {
    var factory = new PromptRequestFactory(PromptBuilder.buildBundle(Path.of("project")));
    var tools = List.<Map<String, Object>>of(Map.of("name", "ReadFile"));

    ContextRequest request =
        factory.createContextRequest(AgentMode.EXECUTE, 1, false, tools, List.of());

    assertEquals(factory.systemPrompt().systemSegments(), request.systemSegments());
    assertEquals(tools, request.tools());
    assertTrue(request.reminder().isPresent());
  }

  @Test
  void injectsDynamicMemoryAndOneShotReminderIntoRequestSnapshot() throws Exception {
    var factory = new PromptRequestFactory(PromptBuilder.buildBundle(Path.of("project")));
    var history = List.of(new Message("user", "fix it"));
    try {
      Class<?> type = Class.forName("com.mewcode.agent.PromptAdditions");
      Object additions =
          type.getConstructor(String.class, Optional.class)
              .newInstance("MEMORY_INDEX", Optional.of(new Message("user", "RESUME_REMINDER")));
      var method =
          PromptRequestFactory.class.getMethod(
              "create", AgentMode.class, int.class, boolean.class, List.class, List.class, type);
      PromptRequest request =
          (PromptRequest)
              method.invoke(factory, AgentMode.EXECUTE, 1, false, history, List.of(), additions);

      assertTrue(
          request.systemSegments().stream().anyMatch(value -> value.contains("MEMORY_INDEX")));
      assertTrue(request.reminder().orElseThrow().textContent().contains("RESUME_REMINDER"));
      assertEquals(history, request.history());
    } catch (ClassNotFoundException | NoSuchMethodException error) {
      fail("PromptAdditions 或动态 create 尚未实现", error);
    } catch (InvocationTargetException error) {
      throw unwrap(error);
    }
  }

  private static Exception unwrap(InvocationTargetException error) {
    Throwable cause = error.getCause();
    if (cause instanceof Exception exception) return exception;
    if (cause instanceof Error fatal) throw fatal;
    return new RuntimeException(cause);
  }
}
