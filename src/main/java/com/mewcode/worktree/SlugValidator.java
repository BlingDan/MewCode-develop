package com.mewcode.worktree;

/** 纯本地名称校验；斜杠编码为不允许直接输入的加号，避免嵌套名称碰撞。 */
public final class SlugValidator {
  private SlugValidator() {}

  public static String validate(String name) {
    if (name == null
        || name.length() < 1
        || name.length() > 64
        || !name.matches("[A-Za-z0-9._/-]+")) {
      throw new IllegalArgumentException("工作树名称须为 1 到 64 个安全字符");
    }
    for (String segment : name.split("/", -1)) {
      if (segment.isEmpty()
          || segment.startsWith(".")
          || segment.contains("..")
          || segment.endsWith(".lock")) {
        throw new IllegalArgumentException("工作树名称包含无效路径或分支段");
      }
    }
    return name.replace('/', '+');
  }

  public static String branch(String name) {
    return "codex/worktree/" + validate(name) + "/task";
  }
}
