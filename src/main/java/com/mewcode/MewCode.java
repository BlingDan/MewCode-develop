package com.mewcode;

import com.mewcode.config.ConfigLoader;
import com.mewcode.config.PermissionConfigLoader;
import com.mewcode.tui.MewCodeModel;
import com.mewcode.tui.tea.Program;
import java.nio.file.Path;

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

    var model =
        new MewCodeModel(
            config.getProviders(),
            projectRoot,
            com.mewcode.llm.LlmClients::create,
            config.getAgent().getLoop(),
            permissions.mode(),
            permissions.ruleEngine(),
            permissions.pathAuthorizationStore(),
            com.mewcode.permission.BashSandboxFactory.create());
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
}
