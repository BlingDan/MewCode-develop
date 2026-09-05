package com.mewcode.tool.support;

import com.mewcode.permission.BashSandbox;
import com.mewcode.permission.BashSandboxFactory;
import com.mewcode.permission.BashSandboxRequest;
import com.mewcode.permission.SandboxedProcess;
import com.mewcode.tool.ToolExecutionContext;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 系统 shell 执行器，负责超时、合并输出和截断。
 *
 * <p>命令的工作目录固定为项目根目录；标准错误合并到标准输出，超过上限的尾部会 被截断并添加标记，避免一次 Bash 调用耗尽模型上下文。
 */
public final class CommandRunner {

  public static final int MAX_OUTPUT_CHARS = 20_000;
  private static final String TRUNCATION_MARKER = "\n[output truncated：超过最大输出长度]";
  private static final Set<String> EXIT_CODE_ONE_IS_NORMAL = Set.of("grep", "diff", "find");
  private final BashSandbox sandbox;

  public CommandRunner() {
    this(BashSandboxFactory.create());
  }

  public CommandRunner(BashSandbox sandbox) {
    this.sandbox = java.util.Objects.requireNonNull(sandbox, "sandbox");
  }

  /** 在项目根目录执行命令，并将超时、中断和截断状态一起返回。 */
  public Result run(String command, ToolExecutionContext context) throws IOException {
    BashSandbox selected =
        context.permissionContext() == null ? sandbox : context.permissionContext().bashSandbox();
    List<Path> writableScopes = List.of(context.projectRoot());
    SandboxedProcess prepared =
        selected.prepare(new BashSandboxRequest(command, context.projectRoot(), writableScopes));
    Process process =
        new ProcessBuilder(prepared.argv())
            .directory(prepared.workingDirectory().toFile())
            .redirectErrorStream(true)
            .start();

    var output = new OutputCollector(MAX_OUTPUT_CHARS);
    Thread reader = Thread.startVirtualThread(() -> readOutput(process.getInputStream(), output));
    boolean finished;
    try {
      finished = process.waitFor(context.timeout().toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException error) {
      process.destroyForcibly();
      Thread.currentThread().interrupt();
      throw new IOException("命令执行被中断", error);
    }
    if (!finished) {
      process.destroyForcibly();
      joinReader(reader);
      return new Result(output.text(), -1, true, output.truncated());
    }
    joinReader(reader);
    return new Result(output.text(), process.exitValue(), false, output.truncated());
  }

  /** 在 Skill 目录运行一个已校验脚本，stdin/stdout/stderr 使用独立的有界通道。 */
  public ScriptResult runScript(
      Path executable, Path workingDirectory, String input, ToolExecutionContext context)
      throws IOException {
    if (context.cancellationToken().isCancelled()) {
      return new ScriptResult("", "", -1, false, true, false);
    }
    BashSandbox selected =
        context.permissionContext() == null ? sandbox : context.permissionContext().bashSandbox();
    String command = "'" + executable.toString().replace("'", "'\"'\"'") + "'";
    SandboxedProcess prepared =
        selected.prepare(
            new BashSandboxRequest(command, workingDirectory, List.of(context.projectRoot())));
    ProcessBuilder builder =
        new ProcessBuilder(prepared.argv()).directory(prepared.workingDirectory().toFile());
    Map<String, String> environment = builder.environment();
    String path = environment.getOrDefault("PATH", "/usr/bin:/bin:/usr/sbin:/sbin");
    String temp = environment.getOrDefault("TMPDIR", "/tmp");
    environment.clear();
    environment.put("PATH", path);
    environment.put("TMPDIR", temp);
    environment.put("MEWCODE_PROJECT_ROOT", context.projectRoot().toString());
    Process process = builder.start();
    try (OutputStream stdin = process.getOutputStream()) {
      stdin.write(input.getBytes(StandardCharsets.UTF_8));
      stdin.write('\n');
    }

    var stdout = new OutputCollector(MAX_OUTPUT_CHARS);
    var stderr = new OutputCollector(MAX_OUTPUT_CHARS);
    Thread outReader =
        Thread.startVirtualThread(() -> readOutput(process.getInputStream(), stdout));
    Thread errorReader =
        Thread.startVirtualThread(() -> readOutput(process.getErrorStream(), stderr));
    long deadline = System.nanoTime() + context.timeout().toNanos();
    boolean timedOut = false;
    boolean cancelled = false;
    try {
      while (process.isAlive()) {
        if (context.cancellationToken().isCancelled()) {
          cancelled = true;
          process.destroyForcibly();
          break;
        }
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
          timedOut = true;
          process.destroyForcibly();
          break;
        }
        process.waitFor(
            Math.min(TimeUnit.NANOSECONDS.toMillis(remaining), 50), TimeUnit.MILLISECONDS);
      }
    } catch (InterruptedException error) {
      process.destroyForcibly();
      Thread.currentThread().interrupt();
      throw new IOException("脚本执行被中断", error);
    }
    joinReader(outReader);
    joinReader(errorReader);
    int exitCode = process.isAlive() ? -1 : process.exitValue();
    return new ScriptResult(
        stdout.text(),
        stderr.text(),
        exitCode,
        timedOut,
        cancelled,
        stdout.truncated() || stderr.truncated());
  }

  /** 判断退出码是否表示工具失败；grep/find 等命令的 1 可表示“没有结果”。 */
  public static boolean isErrorExit(String command, int exitCode) {
    if (exitCode == 0) return false;
    if (exitCode == 1 && EXIT_CODE_ONE_IS_NORMAL.contains(firstCommand(command))) return false;
    return true;
  }

  private static String firstCommand(String command) {
    String[] tokens = command.trim().split("\\s+");
    int index = 0;
    while (index < tokens.length && (tokens[index].equals("env") || tokens[index].contains("="))) {
      index++;
    }
    if (index >= tokens.length) return "";
    String token = tokens[index].replace("'", "").replace("\"", "");
    int slash = token.lastIndexOf('/');
    return slash >= 0 ? token.substring(slash + 1) : token;
  }

  private static void readOutput(InputStream input, OutputCollector output) {
    try (input) {
      byte[] buffer = new byte[8192];
      int count;
      while ((count = input.read(buffer)) >= 0) {
        if (count > 0) output.append(new String(buffer, 0, count, StandardCharsets.UTF_8));
      }
    } catch (IOException ignored) {
      // 子进程被强杀时管道关闭属于正常清理路径。
    }
  }

  private static void joinReader(Thread reader) {
    try {
      reader.join(2_000);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
    }
  }

  public record Result(String output, int exitCode, boolean timedOut, boolean truncated) {}

  public record ScriptResult(
      String stdout,
      String stderr,
      int exitCode,
      boolean timedOut,
      boolean cancelled,
      boolean truncated) {}

  private static final class OutputCollector {
    private final int limit;
    private final StringBuilder text = new StringBuilder();
    private boolean truncated;

    private OutputCollector(int limit) {
      this.limit = limit;
    }

    synchronized void append(String value) {
      if (text.length() < limit) {
        int remaining = limit - text.length();
        text.append(value, 0, Math.min(remaining, value.length()));
      }
      if (text.length() >= limit && value.length() > Math.max(limit - text.length(), 0)) {
        truncated = true;
      }
    }

    synchronized String text() {
      return truncated ? text + TRUNCATION_MARKER : text.toString();
    }

    synchronized boolean truncated() {
      return truncated;
    }
  }
}
