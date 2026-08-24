package com.mewcode.agent;

import com.mewcode.tool.ToolRegistry;
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
}
