package com.mewcode.permission;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/** Bash 危险命令的内置硬拦截器。 */
public final class DangerousCommandBlocklist {
  private static final List<Pattern> BLOCKED =
      List.of(
          Pattern.compile(
              "(?i)(?:^|[;&|()]\\s*)(?:sudo\\s+)?rm\\s+(?=[^;&|()\\n]*-[^;&|()\\s]*r)(?=[^;&|()\\n]*-[^;&|()\\s]*f)[^;&|()\\n]*?(?:--\\s+)?/\\s*(?=$|[;&|()])"),
          Pattern.compile("(?i)(?:^|[;&|()]\\s*)(?:sudo\\s+)?mkfs(?:\\.[\\w]+)?\\s+/dev/"),
          Pattern.compile(
              "(?i)(?:^|[;&|()]\\s*)(?:sudo\\s+)?dd\\s+[^;&|]*\\bof=/dev/(?:disk|sd|nvme|vd)"),
          Pattern.compile("(?i)(?:^|[;&|()]\\s*)(?:sudo\\s+)?chmod\\s+-R\\s+[^;&|]*\\s+/(?:\\s|$)"),
          Pattern.compile(
              "(?i)(?:^|[;&|()]\\s*)(?:sudo\\s+)?chown\\s+-R\\s+[^;&|]*\\s+/(?:\\s|$)"));

  /** 返回命中的完整危险片段；规则集合不可被调用方修改。 */
  public Optional<String> findMatch(String command) {
    if (command == null || command.isBlank()) return Optional.empty();
    return BLOCKED.stream()
        .map(pattern -> pattern.matcher(command))
        .filter(java.util.regex.Matcher::find)
        .map(matcher -> matcher.group().trim())
        .findFirst();
  }

  public String rejectionMessage(String command, String match) {
    String displayed = match == null || match.isBlank() ? command.trim() : match;
    return "操作被拒绝：检测到危险命令 \"" + displayed + "\"。\n" + "此操作可能造成不可逆的系统损坏，已被安全策略硬拦截。";
  }
}
