package com.mewcode.tool.impl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mewcode.tool.FileStateCache;
import com.mewcode.tool.ToolExecutionContext;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LoadSkillToolTest {

  @Test
  void isSystemToolWithNameAndOptionalArgumentsSchema() {
    LoadSkillTool tool = new LoadSkillTool();

    assertTrue(tool.isSystem());
    assertTrue(tool.isReadOnly());
    assertFalse(tool.isDestructive());
    assertTrue(tool.inputSchema().toString().contains("arguments"));
    assertTrue(
        tool.execute(
                new ToolExecutionContext(Path.of("/").toAbsolutePath(), new FileStateCache()),
                Map.of("name", "review"))
            .isError());
  }
}
