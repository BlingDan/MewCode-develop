package com.mewcode;

import com.mewcode.command.CommandRegistry;
import com.mewcode.config.ConfigLoader;
import com.mewcode.config.McpConfigLoader;
import com.mewcode.config.PermissionConfigLoader;
import com.mewcode.mcp.McpManager;
import com.mewcode.skill.ScriptTool;
import com.mewcode.skill.SkillCatalog;
import com.mewcode.tool.Tool;
import com.mewcode.tool.ToolRegistry;
import com.mewcode.tool.impl.LoadSkillTool;
import com.mewcode.tui.MewCodeModel;
import com.mewcode.tui.tea.Program;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** MewCode 终端应用入口，负责加载配置并组装 TUI、provider 和 Agent Loop。 */
public final class MewCode {

  private MewCode() {}

  /** JVM 入口；非零返回码交给 shell，终端清理统一在 {@link #run()} 中完成。 */
  public static void main(String[] args) {
    int exitCode = run();
    if (exitCode != 0) System.exit(exitCode);
  }

  /** 启动一次终端会话并把配置/初始化错误转换为进程退出码。 */
  static int run() {
    Path projectRoot = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
    final com.mewcode.config.AppConfig config;
    try {
      config = ConfigLoader.load(projectRoot.resolve(".mewcode/config.yaml"));
    } catch (ConfigLoader.ConfigException error) {
      System.err.println("MewCode: " + error.getMessage());
      return 2;
    }

    final PermissionConfigLoader.LoadedPermissions permissions;
    try {
      permissions = PermissionConfigLoader.load(projectRoot, config.getPermissions());
    } catch (ConfigLoader.ConfigException error) {
      System.err.println("MewCode: " + error.getMessage());
      return 2;
    }

    McpConfigLoader.Loaded mcp = McpConfigLoader.load(projectRoot, config);
    for (String error : mcp.errors()) System.err.println("MewCode: " + error);

    ToolRegistry registry = ToolRegistry.createDefault();
    registry.register(new LoadSkillTool());
    SkillCatalog catalog =
        SkillCatalog.load(
            projectRoot,
            Path.of(System.getProperty("user.home", ".")).toAbsolutePath().normalize());
    CommandRegistry commands = CommandRegistry.createDefault();
    SkillCatalog.RefreshResult beforeMcp =
        catalog.refresh(registry.ordinaryToolNames(), commands.reservedNames());
    for (String diagnostic : beforeMcp.diagnostics()) {
      System.err.println("MewCode: " + diagnostic);
    }
    ListResult scripts = replaceScripts(registry, beforeMcp);
    if (!scripts.conflicts().isEmpty()) {
      scripts.conflicts().forEach(name -> System.err.println("MewCode: Skill 工具名称冲突：" + name));
      return 2;
    }

    List<SkillCatalog.MissingTool> missingOrdinaryTools =
        beforeMcp.missingTools().stream()
            .filter(missing -> !missing.tool().startsWith("mcp_"))
            .toList();
    if (!missingOrdinaryTools.isEmpty()) {
      printMissingTools(missingOrdinaryTools);
      return 2;
    }

    McpManager mcpManager = new McpManager(registry);
    if (requiresMcpDiscovery(beforeMcp.missingTools())) {
      McpManager.ConnectionReport report = mcpManager.connectAll(mcp.servers());
      report.errors().forEach(error -> System.err.println("MewCode: " + error));
      SkillCatalog.RefreshResult finalSkills =
          catalog.refresh(registry.ordinaryToolNames(), commands.reservedNames());
      scripts = replaceScripts(registry, finalSkills);
      if (!scripts.conflicts().isEmpty() || !finalSkills.missingTools().isEmpty()) {
        scripts.conflicts().forEach(name -> System.err.println("MewCode: Skill 工具名称冲突：" + name));
        printMissingTools(finalSkills.missingTools());
        mcpManager.close();
        return 2;
      }
    }

    final MewCodeModel model;
    try {
      model =
          new MewCodeModel(
              config.getProviders(),
              projectRoot,
              com.mewcode.llm.LlmClients::create,
              config.getAgent().getLoop(),
              permissions.mode(),
              permissions.ruleEngine(),
              permissions.pathAuthorizationStore(),
              com.mewcode.permission.BashSandboxFactory.create(),
              mcp.servers());
      model.useSkillBootstrap(catalog, registry, mcpManager);
    } catch (RuntimeException error) {
      mcpManager.close();
      System.err.println("MewCode: Skill 初始化失败。");
      return 2;
    }
    var program = new Program(model);

    System.out.print("\033[?25l");
    System.out.flush();
    try {
      program.run();
      return 0;
    } catch (RuntimeException error) {
      System.err.println("MewCode: terminal session failed.");
      return 1;
    } finally {
      System.out.print("\033[?25h");
      System.out.flush();
    }
  }

  private static ListResult replaceScripts(
      ToolRegistry registry, SkillCatalog.RefreshResult skills) {
    var tools = new ArrayList<Tool>();
    for (var skill : skills.skills()) {
      for (var spec : skill.tools()) tools.add(new ScriptTool(spec, skill.directory()));
    }
    return new ListResult(registry.replaceSkillTools(tools));
  }

  static boolean requiresMcpDiscovery(List<SkillCatalog.MissingTool> missingTools) {
    return missingTools.stream().anyMatch(missing -> missing.tool().startsWith("mcp_"));
  }

  private static void printMissingTools(List<SkillCatalog.MissingTool> missingTools) {
    missingTools.forEach(
        missing ->
            System.err.println(
                "MewCode: Skill " + missing.skill() + " 引用了不存在的工具 " + missing.tool()));
  }

  private record ListResult(java.util.List<String> conflicts) {}
}
