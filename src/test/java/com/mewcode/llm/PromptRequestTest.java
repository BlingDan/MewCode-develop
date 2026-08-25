package com.mewcode.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.mewcode.conversation.Message;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PromptRequestTest {

  @Test
  void takesImmutableSnapshotsAndKeepsReminderSeparateFromHistory() {
    var system = new ArrayList<>(List.of("stable"));
    var nested = new HashMap<String, Object>();
    nested.put("type", "object");
    var tool = new HashMap<String, Object>();
    tool.put("name", "ReadFile");
    tool.put("input_schema", nested);
    List<Map<String, Object>> tools = new ArrayList<>();
    tools.add(tool);
    var history = new ArrayList<>(List.of(new Message("user", "hello")));
    Message reminder = new Message("user", "<system-reminder>\nnow\n</system-reminder>");

    PromptRequest request = new PromptRequest(system, tools, history, Optional.of(reminder));
    system.add("later");
    tools.getFirst().put("name", "Bash");
    nested.put("type", "array");
    history.add(new Message("assistant", "later"));

    assertEquals(List.of("stable"), request.systemSegments());
    assertEquals("ReadFile", request.tools().getFirst().get("name"));
    assertEquals(
        "object", ((Map<?, ?>) request.tools().getFirst().get("input_schema")).get("type"));
    assertEquals(List.of(new Message("user", "hello")), request.history());
    assertEquals(Optional.of(reminder), request.reminder());
    assertEquals("stable", request.flattenedSystemPrompt());
    assertThrows(
        UnsupportedOperationException.class, () -> request.tools().getFirst().put("x", "y"));
  }
}
