package com.mewcode.tool.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mewcode.subagent.SubAgentTaskManager;
import com.mewcode.tool.Tool;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TaskToolsTest {

  @Test
  void createsListsGetsAndUpdatesManualTasks() throws Exception {
    try (var manager = new SubAgentTaskManager()) {
      var tools = TaskTools.createAll(manager, () -> "session-1");
      Tool create =
          tools.stream().filter(tool -> tool.name().equals("TaskCreate")).findFirst().orElseThrow();
      Tool list =
          tools.stream().filter(tool -> tool.name().equals("TaskList")).findFirst().orElseThrow();
      Tool get =
          tools.stream().filter(tool -> tool.name().equals("TaskGet")).findFirst().orElseThrow();
      Tool update =
          tools.stream().filter(tool -> tool.name().equals("TaskUpdate")).findFirst().orElseThrow();

      var created =
          create.execute(null, Map.of("subject", "inspect", "description", "inspect files"));
      assertFalse(created.isError());
      String taskId = new ObjectMapper().readTree(created.content()).get("task_id").asText();

      assertTrue(list.execute(null, Map.of()).content().contains(taskId));
      assertTrue(get.execute(null, Map.of("task_id", taskId)).content().contains("inspect files"));
      assertFalse(update.execute(null, Map.of("task_id", taskId, "status", "RUNNING")).isError());
      var completed = update.execute(null, Map.of("task_id", taskId, "status", "COMPLETED"));
      assertFalse(completed.isError());
      assertEquals(
          "completed", new ObjectMapper().readTree(completed.content()).get("status").asText());
    }
  }
}
