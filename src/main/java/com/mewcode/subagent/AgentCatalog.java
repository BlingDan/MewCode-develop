package com.mewcode.subagent;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Agent 定义的插件、内置、用户、项目四级不可变快照。 */
public final class AgentCatalog {

  private static final List<String> BUILTINS = List.of("general-purpose", "plan", "explore");

  private final Snapshot snapshot;

  private AgentCatalog(Snapshot snapshot) {
    this.snapshot = snapshot;
  }

  public static AgentCatalog load(
      Path projectRoot, Path userHome, List<Path> pluginDirs, int defaultMaxTurns) {
    Path project = projectRoot.toAbsolutePath().normalize();
    Path user = userHome.toAbsolutePath().normalize();
    var diagnostics = new ArrayList<String>();
    var candidates = new LinkedHashMap<String, List<SubAgentSpec>>();
    List<Path> plugins = pluginDirs == null ? List.of() : List.copyOf(pluginDirs);
    for (Path directory : plugins) {
      scan(directory, SubAgentSpec.Source.PLUGIN, defaultMaxTurns, diagnostics)
          .forEach(spec -> addCandidate(candidates, spec));
    }
    loadBuiltins(defaultMaxTurns, diagnostics).forEach(spec -> addCandidate(candidates, spec));
    scan(user.resolve(".mewcode/agents"), SubAgentSpec.Source.USER, defaultMaxTurns, diagnostics)
        .forEach(spec -> addCandidate(candidates, spec));
    scan(
            project.resolve(".mewcode/agents"),
            SubAgentSpec.Source.PROJECT,
            defaultMaxTurns,
            diagnostics)
        .forEach(spec -> addCandidate(candidates, spec));

    var selected = new LinkedHashMap<String, SubAgentSpec>();
    candidates.forEach(
        (name, versions) -> {
          if (!versions.isEmpty()) selected.put(name, versions.getLast());
        });
    var ordered =
        selected.values().stream().sorted(Comparator.comparing(SubAgentSpec::name)).toList();
    return new AgentCatalog(new Snapshot(Map.copyOf(selected), ordered, diagnostics));
  }

  public Optional<SubAgentSpec> find(String name) {
    return name == null ? Optional.empty() : Optional.ofNullable(snapshot.byName().get(name));
  }

  public List<SubAgentSpec> list() {
    return snapshot.ordered();
  }

  public List<String> diagnostics() {
    return snapshot.diagnostics();
  }

  public String promptSummary() {
    if (snapshot.ordered().isEmpty()) return "";
    var text = new StringBuilder("# 可用 SubAgent\n\n可将独立任务委派给以下角色：\n");
    for (SubAgentSpec spec : snapshot.ordered()) {
      text.append("- ")
          .append(spec.name())
          .append(": ")
          .append(spec.description().replace('\n', ' '))
          .append('\n');
    }
    return text.toString().stripTrailing();
  }

  /** 仅生成诊断，不改变既有不可变快照。 */
  public List<String> diagnoseUnknownTools(Set<String> availableTools) {
    Set<String> available = availableTools == null ? Set.of() : Set.copyOf(availableTools);
    var result = new ArrayList<String>();
    for (SubAgentSpec spec : snapshot.ordered()) {
      var unknown = new ArrayList<String>();
      for (String tool : spec.tools() == null ? Set.<String>of() : spec.tools()) {
        if (!available.contains(tool)) unknown.add(tool);
      }
      if (!unknown.isEmpty()) {
        result.add(spec.sourcePath() + "：引用未知工具 " + String.join(", ", unknown));
      }
    }
    return List.copyOf(result);
  }

  private static List<SubAgentSpec> loadBuiltins(int defaultMaxTurns, List<String> diagnostics) {
    var result = new ArrayList<SubAgentSpec>();
    for (String name : BUILTINS) {
      String resource = "/agents/builtin/" + name + ".md";
      try (InputStream input = AgentCatalog.class.getResourceAsStream(resource)) {
        if (input == null) {
          diagnostics.add("内置 Agent 资源缺失：" + name);
          continue;
        }
        Path marker = Path.of("/classpath/agents/builtin", name + ".md");
        result.add(
            AgentDefinitionParser.parse(
                new String(input.readAllBytes(), StandardCharsets.UTF_8),
                marker,
                SubAgentSpec.Source.BUILTIN,
                defaultMaxTurns));
      } catch (IOException | AgentDefinitionParser.ParseException error) {
        diagnostics.add("内置 Agent " + name + "：" + safeReason(error));
      }
    }
    return result;
  }

  private static List<SubAgentSpec> scan(
      Path root, SubAgentSpec.Source source, int defaultMaxTurns, List<String> diagnostics) {
    if (root == null || !Files.isDirectory(root)) return List.of();
    var entries = new ArrayList<Path>();
    try (var stream = Files.list(root)) {
      stream
          .filter(
              path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".md"))
          .sorted(Comparator.comparing(path -> path.getFileName().toString()))
          .forEach(entries::add);
    } catch (IOException error) {
      diagnostics.add(root + "：Agent 目录读取失败");
      return List.of();
    }
    var result = new ArrayList<SubAgentSpec>();
    for (Path entry : entries) {
      try {
        result.add(AgentDefinitionParser.parse(entry, source, defaultMaxTurns));
      } catch (AgentDefinitionParser.ParseException error) {
        diagnostics.add(error.getMessage());
      }
    }
    return result;
  }

  private static void addCandidate(Map<String, List<SubAgentSpec>> candidates, SubAgentSpec spec) {
    candidates.computeIfAbsent(spec.name(), ignored -> new ArrayList<>()).add(spec);
  }

  private static String safeReason(Throwable error) {
    return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
  }

  private record Snapshot(
      Map<String, SubAgentSpec> byName, List<SubAgentSpec> ordered, List<String> diagnostics) {
    private Snapshot {
      byName = Map.copyOf(byName);
      ordered = List.copyOf(ordered);
      diagnostics = List.copyOf(diagnostics);
    }
  }
}
