package com.mewcode.command;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** 仅解释用户直接输入的斜杠命令，不运行 Git，也不产生授权能力。 */
public final class WorktreeCommand {
  private WorktreeCommand() {}

  public static Map<String, Object> parse(String text) {
    String[] parts = text == null || text.isBlank() ? new String[0] : text.strip().split("\\s+");
    if (parts.length == 0
        || !Set.of("create", "enter", "list", "exit", "delete").contains(parts[0])) throw usage();
    String action = parts[0];
    var result = new LinkedHashMap<String, Object>();
    result.put("action", action);
    int index = 1;
    if (Set.of("create", "enter", "delete").contains(action)) {
      if (index == parts.length || parts[index].startsWith("--")) throw usage();
      result.put("name", parts[index++]);
    }
    boolean discard = false;
    boolean delete = false;
    while (index < parts.length) {
      switch (parts[index++]) {
        case "--delete" -> {
          if (!action.equals("exit") || delete) throw usage();
          delete = true;
          result.put("delete", true);
        }
        case "--discard" -> {
          if (discard) throw usage();
          discard = true;
          result.put("discardChanges", true);
        }
        default -> throw usage();
      }
    }
    if (discard && !(action.equals("delete") || action.equals("exit") && delete)) throw usage();
    return Map.copyOf(result);
  }

  private static IllegalArgumentException usage() {
    return new IllegalArgumentException(
        "用法：/worktree create|enter|delete <name>，list，exit [--delete]；明确删除时可加 --discard。");
  }
}
