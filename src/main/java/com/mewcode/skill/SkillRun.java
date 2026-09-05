package com.mewcode.skill;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** 一次用户请求中的 Skill 激活快照；请求结束即丢弃。 */
public final class SkillRun {

  private final LinkedHashMap<String, ActiveSkill> active = new LinkedHashMap<>();

  /** 渲染参数并激活定义；同名定义显式重载时原位替换。 */
  public synchronized void activate(SkillDefinition skill, String arguments) {
    active.put(
        skill.meta().name(),
        new ActiveSkill(skill, render(skill.body(), arguments == null ? "" : arguments)));
  }

  public synchronized List<ActiveSkill> activeSkills() {
    return List.copyOf(active.values());
  }

  public synchronized Set<String> allowedTools() {
    var result = new LinkedHashSet<String>();
    active.values().forEach(item -> result.addAll(item.definition().meta().tools()));
    return Set.copyOf(result);
  }

  public synchronized Optional<String> preferredProvider() {
    String selected = null;
    for (ActiveSkill item : active.values()) {
      if (item.definition().meta().model() != null) selected = item.definition().meta().model();
    }
    return Optional.ofNullable(selected);
  }

  /** 作为最后一个 system segment 注入的完整 SOP 块。 */
  public synchronized String promptBlock() {
    if (active.isEmpty()) return "";
    var result = new StringBuilder("# 当前已激活 Skills\n");
    for (ActiveSkill item : active.values()) {
      result
          .append("\n## ")
          .append(item.definition().meta().name())
          .append("\n\n")
          .append(item.renderedBody())
          .append('\n');
    }
    return result.toString().stripTrailing();
  }

  public synchronized void clear() {
    active.clear();
  }

  static String render(String body, String arguments) {
    if (body.contains("{{arguments}}")) return body.replace("{{arguments}}", arguments);
    if (arguments.isBlank()) return body;
    return body.stripTrailing() + "\n\n## 用户输入\n\n" + arguments;
  }

  public record ActiveSkill(SkillDefinition definition, String renderedBody) {}
}
