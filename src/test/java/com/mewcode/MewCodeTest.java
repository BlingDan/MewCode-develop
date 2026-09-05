package com.mewcode;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mewcode.skill.SkillCatalog.MissingTool;
import java.util.List;
import org.junit.jupiter.api.Test;

class MewCodeTest {

  @Test
  void blocksStartupForReferencedMcpToolsOnly() {
    assertFalse(MewCode.requiresMcpDiscovery(List.of()));
    assertFalse(MewCode.requiresMcpDiscovery(List.of(new MissingTool("bad", "UnknownTool"))));
    assertTrue(
        MewCode.requiresMcpDiscovery(List.of(new MissingTool("needs-mcp", "mcp_demo_search"))));
  }
}
