package com.mewcode.tool;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolRegistryTest {

  @Test
  void registersSixToolsAndBuildsBothProviderFormats() {
    ToolRegistry registry = ToolRegistry.createDefault();

    assertEquals(
        List.of("ReadFile", "WriteFile", "EditFile", "Bash", "Glob", "Grep"),
        registry.getAll().stream().map(Tool::name).toList());

    Map<String, Object> anthropic = registry.toAPIFormate(ToolApiProtocol.ANTHROPIC).getFirst();
    assertEquals("ReadFile", anthropic.get("name"));
    assertTrue(anthropic.containsKey("input_schema"));
    assertFalse(anthropic.containsKey("function"));

    Map<String, Object> openAi = registry.toAPIFormate(ToolApiProtocol.OPENAI).getFirst();
    assertEquals("function", openAi.get("type"));
    assertEquals("ReadFile", ((Map<?, ?>) openAi.get("function")).get("name"));
    assertTrue(((Map<?, ?>) openAi.get("function")).containsKey("parameters"));
  }

  @Test
  void exposesPermissionMetadataAndConcurrencyPolicy() {
    ToolRegistry registry = ToolRegistry.createDefault();

    Tool read = registry.get("ReadFile").orElseThrow();
    assertTrue(read.isReadOnly());
    assertFalse(read.isDestructive());
    assertTrue(read.isConcurrencySafe(Map.of()));
    assertEquals(ToolCategory.FILE, read.category());

    Tool bash = registry.get("Bash").orElseThrow();
    assertFalse(bash.isReadOnly());
    assertTrue(bash.isDestructive());
    assertFalse(bash.isConcurrencySafe(Map.of()));
    assertEquals(ToolCategory.SHELL, bash.category());

    assertEquals(1, registry.getAll().stream().filter(Tool::isDestructive).count());
  }

  @Test
  void toolResultMetadataIsAnImmutableLocalSnapshot() {
    var source = new HashMap<String, Object>();
    source.put("exitCode", 0);
    ToolResult result = new ToolResult("ok", false, source);
    source.put("exitCode", 1);

    assertEquals(0, result.metadata().get("exitCode"));
    assertThrows(UnsupportedOperationException.class, () -> result.metadata().put("extra", true));
  }

  @Test
  void replacingByNameKeepsRegistrationSlot() {
    ToolRegistry registry = new ToolRegistry();
    registry.register(new StubTool("one", ToolCategory.FILE));
    registry.register(new StubTool("two", ToolCategory.SEARCH));
    registry.register(new StubTool("one", ToolCategory.SHELL));

    assertEquals(List.of("one", "two"), registry.getAll().stream().map(Tool::name).toList());
    assertEquals(ToolCategory.SHELL, registry.get("one").orElseThrow().category());
  }

  @Test
  void replacesSkillToolsAsOneGroupWithoutOverwritingOrdinaryTools() {
    ToolRegistry registry = ToolRegistry.createDefault();

    assertEquals(
        List.of(), registry.replaceSkillTools(List.of(new StubTool("custom", ToolCategory.SHELL))));
    assertTrue(registry.get("custom").isPresent());

    assertEquals(
        List.of("ReadFile"),
        registry.replaceSkillTools(List.of(new StubTool("ReadFile", ToolCategory.SHELL))));
    assertTrue(registry.get("custom").isPresent());
    assertEquals(ToolCategory.FILE, registry.get("ReadFile").orElseThrow().category());

    assertEquals(
        List.of(), registry.replaceSkillTools(List.of(new StubTool("other", ToolCategory.SHELL))));
    assertFalse(registry.get("custom").isPresent());
    assertTrue(registry.get("other").isPresent());
  }

  @Test
  void apiDescriptionsRepeatTheCriticalToolRulesWithoutChangingSchemas() {
    ToolRegistry registry = ToolRegistry.createDefault();

    String editDescription =
        (String)
            registry.toAPIFormate(ToolApiProtocol.ANTHROPIC).stream()
                .filter(tool -> "EditFile".equals(tool.get("name")))
                .findFirst()
                .orElseThrow()
                .get("description");
    String writeDescription =
        (String)
            registry.toAPIFormate(ToolApiProtocol.ANTHROPIC).stream()
                .filter(tool -> "WriteFile".equals(tool.get("name")))
                .findFirst()
                .orElseThrow()
                .get("description");
    String bashDescription =
        (String)
            registry.toAPIFormate(ToolApiProtocol.OPENAI).stream()
                .filter(tool -> "Bash".equals(((Map<?, ?>) tool.get("function")).get("name")))
                .findFirst()
                .orElseThrow()
                .get("function")
                .toString();

    assertTrue(ToolPromptRules.globalInstructions().contains("专用工具"));
    assertTrue(editDescription.contains(ToolPromptRules.editingRule()));
    assertTrue(writeDescription.contains(ToolPromptRules.editingRule()));
    assertTrue(bashDescription.contains(ToolPromptRules.dedicatedToolRule()));
  }

  private record StubTool(String name, ToolCategory category) implements Tool {
    @Override
    public String description() {
      return name;
    }

    @Override
    public Map<String, Object> inputSchema() {
      return Map.of("type", "object");
    }

    @Override
    public ToolResult execute(ToolExecutionContext context, Map<String, Object> input) {
      return ToolResult.success("ok");
    }

    @Override
    public boolean isReadOnly() {
      return category == ToolCategory.FILE;
    }

    @Override
    public boolean isDestructive() {
      return category == ToolCategory.SHELL;
    }

    @Override
    public boolean isConcurrencySafe(Map<String, Object> input) {
      return true;
    }

    @Override
    public String validateInput(Map<String, Object> input) {
      return null;
    }
  }
}
