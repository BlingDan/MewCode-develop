package com.mewcode.skill;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Skill 的三级发现、覆盖和热刷新目录。 */
public final class SkillCatalog {

  private static final List<String> BUILTINS = List.of("commit", "review", "test");

  private final Path projectRoot;
  private final Path userHome;
  private volatile Snapshot snapshot = new Snapshot(Map.of(), List.of());

  private SkillCatalog(Path projectRoot, Path userHome) {
    this.projectRoot = projectRoot.toAbsolutePath().normalize();
    this.userHome = userHome.toAbsolutePath().normalize();
  }

  public static SkillCatalog load(Path projectRoot, Path userHome) {
    var catalog = new SkillCatalog(projectRoot, userHome);
    catalog.refresh(Set.of(), Set.of());
    return catalog;
  }

  /** 全量重扫并原子替换目录快照；单个坏定义只形成诊断。 */
  public synchronized RefreshResult refresh(
      Set<String> ordinaryToolNames, Set<String> reservedCommands) {
    return refreshInternal(ordinaryToolNames, reservedCommands, false);
  }

  /** 热更新时跳过引用未知工具的定义，并重新选择低优先级有效版本。 */
  public synchronized RefreshResult refreshHot(
      Set<String> ordinaryToolNames, Set<String> reservedCommands) {
    return refreshInternal(ordinaryToolNames, reservedCommands, true);
  }

  private RefreshResult refreshInternal(
      Set<String> ordinaryToolNames, Set<String> reservedCommands, boolean hot) {
    var diagnostics = new ArrayList<String>();
    Map<String, List<SkillDefinition>> candidates = new LinkedHashMap<>();
    loadBuiltins(diagnostics).forEach(skill -> addCandidate(candidates, skill));
    scan(userHome.resolve(".mewcode/skills"), SkillDefinition.Source.USER, diagnostics)
        .forEach(skill -> addCandidate(candidates, skill));
    scan(projectRoot.resolve(".mewcode/skills"), SkillDefinition.Source.PROJECT, diagnostics)
        .forEach(skill -> addCandidate(candidates, skill));

    Set<String> reserved = lower(reservedCommands);
    candidates
        .entrySet()
        .removeIf(
            entry -> {
              if (!reserved.contains(entry.getKey())) return false;
              diagnostics.add(entry.getValue().getLast().entry() + "：Skill 名称与保留命令冲突，已跳过");
              return true;
            });

    Set<String> ordinary = ordinaryToolNames == null ? Set.of() : Set.copyOf(ordinaryToolNames);
    Map<String, SkillDefinition> merged = selectWinners(candidates);
    boolean conflicted;
    do {
      conflicted = false;
      var owners = new LinkedHashMap<String, String>();
      var invalid = new LinkedHashMap<String, LinkedHashSet<String>>();
      for (SkillDefinition skill : merged.values()) {
        for (SkillDefinition.ToolSpec tool : skill.tools()) {
          if (ordinary.contains(tool.name())) {
            invalid
                .computeIfAbsent(skill.meta().name(), ignored -> new LinkedHashSet<>())
                .add(tool.name());
          }
          String previous = owners.putIfAbsent(tool.name(), skill.meta().name());
          if (previous != null) {
            invalid.computeIfAbsent(previous, ignored -> new LinkedHashSet<>()).add(tool.name());
            invalid
                .computeIfAbsent(skill.meta().name(), ignored -> new LinkedHashSet<>())
                .add(tool.name());
          }
        }
      }
      for (var entry : invalid.entrySet()) {
        SkillDefinition rejected = merged.get(entry.getKey());
        diagnostics.add(
            rejected.entry()
                + "：专属工具名称冲突 "
                + String.join(", ", entry.getValue())
                + "，已跳过并尝试低优先级版本");
        List<SkillDefinition> versions = candidates.get(entry.getKey());
        versions.removeLast();
        if (versions.isEmpty()) candidates.remove(entry.getKey());
        conflicted = true;
      }
      if (conflicted) merged = selectWinners(candidates);
    } while (conflicted);

    if (hot) {
      boolean removed;
      do {
        removed = false;
        Set<String> available = new LinkedHashSet<>(ordinary);
        merged.values().forEach(skill -> skill.tools().forEach(tool -> available.add(tool.name())));
        for (var entry : new ArrayList<>(merged.entrySet())) {
          List<String> missing =
              entry.getValue().meta().tools().stream()
                  .filter(tool -> !available.contains(tool))
                  .toList();
          if (missing.isEmpty()) continue;
          diagnostics.add(
              entry.getValue().entry() + "：引用未知工具 " + String.join(", ", missing) + "，已跳过并尝试低优先级版本");
          List<SkillDefinition> versions = candidates.get(entry.getKey());
          versions.removeLast();
          if (versions.isEmpty()) candidates.remove(entry.getKey());
          removed = true;
        }
        if (removed) merged = selectWinners(candidates);
      } while (removed);
    }

    List<SkillDefinition> ordered =
        merged.values().stream()
            .sorted(Comparator.comparing(skill -> skill.meta().name()))
            .toList();
    var byName = new LinkedHashMap<String, SkillDefinition>();
    ordered.forEach(skill -> byName.put(skill.meta().name(), skill));
    Snapshot next = new Snapshot(Map.copyOf(byName), ordered);
    boolean changed = !snapshot.equals(next);
    snapshot = next;

    Set<String> available = new LinkedHashSet<>(ordinary);
    ordered.forEach(skill -> skill.tools().forEach(tool -> available.add(tool.name())));
    var missing = new ArrayList<MissingTool>();
    for (SkillDefinition skill : ordered) {
      for (String tool : skill.meta().tools()) {
        if (!available.contains(tool)) missing.add(new MissingTool(skill.meta().name(), tool));
      }
    }
    List<SkillDefinition.ToolSpec> scriptTools =
        ordered.stream().flatMap(skill -> skill.tools().stream()).toList();
    return new RefreshResult(changed, ordered, scriptTools, diagnostics, missing);
  }

  public Optional<SkillDefinition> find(String name) {
    if (name == null) return Optional.empty();
    return Optional.ofNullable(snapshot.byName().get(name.toLowerCase(Locale.ROOT)));
  }

  public List<SkillDefinition> list() {
    return snapshot.ordered();
  }

  /** 第一阶段提示，仅包含名称和一句说明。 */
  public String promptSummary() {
    if (snapshot.ordered().isEmpty()) return "";
    var text = new StringBuilder("# 可用 Skills\n\n需要时调用 LoadSkill 加载完整指令：\n");
    snapshot
        .ordered()
        .forEach(
            skill ->
                text.append("- ")
                    .append(skill.meta().name())
                    .append(": ")
                    .append(skill.meta().description().replace('\n', ' '))
                    .append('\n'));
    return text.toString().stripTrailing();
  }

  private List<SkillDefinition> loadBuiltins(List<String> diagnostics) {
    var result = new ArrayList<SkillDefinition>();
    for (String name : BUILTINS) {
      String resource = "/skills/builtin/" + name + ".md";
      try (InputStream input = SkillCatalog.class.getResourceAsStream(resource)) {
        if (input == null) {
          diagnostics.add("内置 Skill 资源缺失：" + name);
          continue;
        }
        result.add(
            SkillParser.parseBuiltin(
                name + ".md", new String(input.readAllBytes(), StandardCharsets.UTF_8)));
      } catch (IOException | SkillParser.ParseException error) {
        diagnostics.add("内置 Skill " + name + "：" + safeReason(error));
      }
    }
    return result;
  }

  private static List<SkillDefinition> scan(
      Path root, SkillDefinition.Source source, List<String> diagnostics) {
    if (!Files.isDirectory(root)) return List.of();
    var entries = new ArrayList<Path>();
    try (var stream = Files.list(root)) {
      stream
          .sorted(Comparator.comparing(path -> path.getFileName().toString()))
          .forEach(
              path -> {
                if (Files.isRegularFile(path) && path.getFileName().toString().endsWith(".md")) {
                  entries.add(path);
                } else if (Files.isDirectory(path)
                    && Files.isRegularFile(path.resolve("SKILL.md"))) {
                  entries.add(path.resolve("SKILL.md"));
                }
              });
    } catch (IOException error) {
      diagnostics.add(root + "：Skill 目录读取失败（" + error.getClass().getSimpleName() + "）");
      return List.of();
    }
    var result = new ArrayList<SkillDefinition>();
    for (Path entry : entries) {
      try {
        result.add(SkillParser.parse(entry, source));
      } catch (SkillParser.ParseException error) {
        diagnostics.add(error.getMessage());
      }
    }
    return result;
  }

  private static void addCandidate(
      Map<String, List<SkillDefinition>> candidates, SkillDefinition skill) {
    candidates.computeIfAbsent(skill.meta().name(), ignored -> new ArrayList<>()).add(skill);
  }

  private static Map<String, SkillDefinition> selectWinners(
      Map<String, List<SkillDefinition>> candidates) {
    var selected = new LinkedHashMap<String, SkillDefinition>();
    candidates.forEach(
        (name, versions) -> {
          if (!versions.isEmpty()) selected.put(name, versions.getLast());
        });
    return selected;
  }

  private static Set<String> lower(Set<String> values) {
    if (values == null || values.isEmpty()) return Set.of();
    var result = new LinkedHashSet<String>();
    values.forEach(value -> result.add(value.toLowerCase(Locale.ROOT)));
    return Set.copyOf(result);
  }

  private static String safeReason(Throwable error) {
    return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
  }

  private record Snapshot(Map<String, SkillDefinition> byName, List<SkillDefinition> ordered) {}

  public record MissingTool(String skill, String tool) {}

  public record RefreshResult(
      boolean changed,
      List<SkillDefinition> skills,
      List<SkillDefinition.ToolSpec> scriptTools,
      List<String> diagnostics,
      List<MissingTool> missingTools) {

    public RefreshResult {
      skills = List.copyOf(skills);
      scriptTools = List.copyOf(scriptTools);
      diagnostics = List.copyOf(diagnostics);
      missingTools = List.copyOf(missingTools);
    }
  }
}
