package com.mewcode.agent;

import com.mewcode.tool.ToolRegistry;
import com.mewcode.tool.Tool;
import com.mewcode.tool.ToolCategory;
import com.mewcode.tool.ToolExecutionContext;
import com.mewcode.tool.ToolResult;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ToolPolicyTest {

    @Test
    void planModeAllowsAllSafeReadOnlyToolsAndExecuteModeAllowsAllTools() {
        var registry = ToolRegistry.createDefault();
        var plan = ToolPolicy.forMode(AgentMode.PLAN);
        var execute = ToolPolicy.forMode(AgentMode.EXECUTE);

        assertTrue(plan.isAllowed(registry.get("ReadFile").orElseThrow()));
        assertTrue(plan.isAllowed(registry.get("Glob").orElseThrow()));
        assertTrue(plan.isAllowed(registry.get("Grep").orElseThrow()));
        assertFalse(plan.isAllowed(registry.get("WriteFile").orElseThrow()));
        assertFalse(plan.isAllowed(registry.get("EditFile").orElseThrow()));
        assertFalse(plan.isAllowed(registry.get("Bash").orElseThrow()));
        assertTrue(execute.isAllowed(registry.get("Bash").orElseThrow()));
    }

    @Test
    void activeSkillIntersectsWhitelistWithModeButAlwaysAllowsSystemTools() {
        var registry = ToolRegistry.createDefault();
        Tool system = new SystemTool();

        var execute = ToolPolicy.forModeAndTools(AgentMode.EXECUTE, Set.of("ReadFile", "Bash"), true);
        var plan = ToolPolicy.forModeAndTools(AgentMode.PLAN, Set.of("ReadFile", "Bash"), true);

        assertTrue(execute.isAllowed(registry.get("ReadFile").orElseThrow()));
        assertTrue(execute.isAllowed(registry.get("Bash").orElseThrow()));
        assertFalse(execute.isAllowed(registry.get("Glob").orElseThrow()));
        assertTrue(plan.isAllowed(registry.get("ReadFile").orElseThrow()));
        assertFalse(plan.isAllowed(registry.get("Bash").orElseThrow()));
        assertTrue(plan.isAllowed(system));
        assertFalse(ToolPolicy.forMode(AgentMode.EXECUTE).isAllowed(new SkillOnlyTool()));
    }

    private static class SystemTool implements Tool {
        public String name() { return "LoadSkill"; }
        public String description() { return "load"; }
        public ToolCategory category() { return ToolCategory.SEARCH; }
        public Map<String, Object> inputSchema() { return Map.of("type", "object"); }
        public ToolResult execute(ToolExecutionContext context, Map<String, Object> input) { return ToolResult.success("ok"); }
        public boolean isReadOnly() { return true; }
        public boolean isDestructive() { return false; }
        public boolean isConcurrencySafe(Map<String, Object> input) { return true; }
        public String validateInput(Map<String, Object> input) { return null; }
        public boolean isSystem() { return true; }
    }

    private static final class SkillOnlyTool extends SystemTool {
        @Override public String name() { return "skill_only"; }
        @Override public boolean isSystem() { return false; }
        @Override public boolean isSkillTool() { return true; }
    }
}
