package com.mewcode.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mewcode.tool.impl.BashTool;
import com.mewcode.tool.impl.EditFileTool;
import com.mewcode.tool.impl.WriteFileTool;
import org.junit.jupiter.api.Test;

class ToolPromptRulesTest {

  @Test
  void strengthensOnlyTheDescriptionAndKeepsExistingText() {
    var edit = new EditFileTool();
    var bash = new BashTool();
    var write = new WriteFileTool();

    assertTrue(ToolPromptRules.descriptionFor(edit).contains(edit.description()));
    assertTrue(ToolPromptRules.descriptionFor(edit).contains(ToolPromptRules.editingRule()));
    assertTrue(ToolPromptRules.descriptionFor(write).contains(ToolPromptRules.editingRule()));
    assertTrue(ToolPromptRules.descriptionFor(bash).contains(ToolPromptRules.dedicatedToolRule()));
    assertEquals("test", ToolPromptRules.descriptionFor(new StubTool()));
  }

  private static final class StubTool implements Tool {
    @Override
    public String name() {
      return "Stub";
    }

    @Override
    public String description() {
      return "test";
    }

    @Override
    public ToolCategory category() {
      return ToolCategory.SEARCH;
    }

    @Override
    public java.util.Map<String, Object> inputSchema() {
      return java.util.Map.of("type", "object");
    }

    @Override
    public ToolResult execute(ToolExecutionContext context, java.util.Map<String, Object> input) {
      return ToolResult.success("ok");
    }

    @Override
    public boolean isReadOnly() {
      return true;
    }

    @Override
    public boolean isDestructive() {
      return false;
    }

    @Override
    public boolean isConcurrencySafe(java.util.Map<String, Object> input) {
      return true;
    }

    @Override
    public String validateInput(java.util.Map<String, Object> input) {
      return null;
    }
  }
}
