package com.mewcode.tool.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentToolTest {

  @Test
  void keepsAStableSchemaAndRejectsDirectExecution() {
    var tool = new AgentTool();
    var schema = tool.inputSchema();

    assertEquals("Agent", tool.name());
    assertTrue(schema.toString().contains("subagent_type"));
    assertTrue(schema.toString().contains("run_in_background"));
    assertEquals(null, tool.validateInput(Map.of("prompt", "work", "description", "description")));
    assertTrue(tool.validateInput(Map.of("prompt", "work")).contains("description"));
    assertTrue(tool.execute(null, Map.of()).isError());
  }
}
