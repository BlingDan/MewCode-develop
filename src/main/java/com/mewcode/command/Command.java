package com.mewcode.command;

import java.util.List;
import java.util.Objects;

/** 一条斜杠命令的不可变元数据。 */
public record Command(
    String name,
    List<String> aliases,
    String description,
    String usage,
    CommandType type,
    String argumentHint,
    boolean hidden) {

  public Command {
    if (name == null || name.isBlank()) throw new IllegalArgumentException("命令名称不能为空");
    aliases = List.copyOf(Objects.requireNonNull(aliases, "aliases"));
    if (description == null || description.isBlank()) {
      throw new IllegalArgumentException("命令描述不能为空");
    }
    if (usage == null || usage.isBlank()) throw new IllegalArgumentException("命令用法不能为空");
    Objects.requireNonNull(type, "type");
    argumentHint = argumentHint == null ? "" : argumentHint;
  }

  public enum CommandType {
    LOCAL,
    LOCAL_UI,
    PROMPT
  }
}
