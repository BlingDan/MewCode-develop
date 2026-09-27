package com.mewcode.tool.support;

import com.mewcode.agent.CancellationToken;
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
    SandboxedProcess prepared =
        selected.prepare(sandboxRequest(command, context.projectRoot(), context, List.of()));
    protectUncontainedLaunch(command, context, selected);
    ProcessBuilder builder =
        new ProcessBuilder(prepared.argv())
            .directory(prepared.workingDirectory().toFile())
            .redirectErrorStream(true);
    applyEnvironment(builder.environment(), context);
    Process process = builder.start();
    var output = new OutputCollector(MAX_OUTPUT_CHARS);
    Thread reader = Thread.startVirtualThread(() -> readOutput(process.getInputStream(), output));
    ProcessWait completed = waitForProcess(process, context);
    if (completed.timedOut() || completed.cancelled()) {
      joinReader(reader);
      requireReadersStopped(context, reader);
      return new Result(output.text(), -1, completed.timedOut(), output.truncated());
    }
    joinReader(reader);
    requireReadersStopped(context, reader);
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
    Path skillDirectory = workingDirectory.toAbsolutePath().normalize();
    Path actualCwd =
        context.workspaceScope() != null && context.workspaceScope().isolated()
            ? context.projectRoot()
            : workingDirectory;
    SandboxedProcess prepared =
        selected.prepare(sandboxRequest(command, actualCwd, context, List.of(skillDirectory)));
    // 任意脚本可以在内部 double-fork；仅靠调用者命令文本不能证明收口。
    protectUncontainedLaunch(null, context, selected);
    ProcessBuilder builder =
        new ProcessBuilder(prepared.argv()).directory(prepared.workingDirectory().toFile());
    Map<String, String> environment = builder.environment();
    Map<String, String> inherited = Map.copyOf(environment);
    String path = environment.getOrDefault("PATH", "/usr/bin:/bin:/usr/sbin:/sbin");
    String temp = environment.getOrDefault("TMPDIR", "/tmp");
    environment.clear();
    environment.put("PATH", path);
    environment.put("TMPDIR", temp);
    environment.put("MEWCODE_PROJECT_ROOT", context.projectRoot().toString());
    environment.put("MEWCODE_SKILL_DIR", skillDirectory.toString());
    if (context.workspaceScope() != null && context.workspaceScope().isolated()) {
      inherited.forEach(
          (key, value) -> {
            if (key.equals("GIT_CONFIG_COUNT")
                || key.startsWith("GIT_CONFIG_KEY_")
                || key.startsWith("GIT_CONFIG_VALUE_")) environment.put(key, value);
          });
    }
    applyEnvironment(environment, context);
    Process process = builder.start();
    Thread inputWriter =
        Thread.startVirtualThread(
            () -> {
              try (OutputStream stdin = process.getOutputStream()) {
                stdin.write(input.getBytes(StandardCharsets.UTF_8));
                stdin.write('\n');
              } catch (IOException ignored) {
              }
            });

    var stdout = new OutputCollector(MAX_OUTPUT_CHARS);
    var stderr = new OutputCollector(MAX_OUTPUT_CHARS);
    Thread outReader =
        Thread.startVirtualThread(() -> readOutput(process.getInputStream(), stdout));
    Thread errorReader =
        Thread.startVirtualThread(() -> readOutput(process.getErrorStream(), stderr));
    ProcessWait completed = waitForProcess(process, context);
    boolean timedOut = completed.timedOut();
    boolean cancelled = completed.cancelled();
    joinReader(inputWriter);
    joinReader(outReader);
    joinReader(errorReader);
    requireReadersStopped(context, inputWriter, outReader, errorReader);
    int exitCode = process.isAlive() ? -1 : process.exitValue();
    return new ScriptResult(
        stdout.text(),
        stderr.text(),
        exitCode,
        timedOut,
        cancelled,
        stdout.truncated() || stderr.truncated());
  }

  /** 在项目目录的 OS 沙箱中执行 Hook，stdin、stdout、stderr 保持独立。 */
  public ScriptResult runHook(
      String command,
      Path workingDirectory,
      String input,
      java.time.Duration timeout,
      CancellationToken cancellation)
      throws IOException {
    return runHook(
        command,
        input,
        new ToolExecutionContext(
            workingDirectory, timeout, new com.mewcode.tool.FileStateCache(), cancellation));
  }

  public ScriptResult runHook(String command, String input, ToolExecutionContext context)
      throws IOException {
    java.time.Duration timeout = context.timeout();
    CancellationToken cancellation = context.cancellationToken();
    Path workingDirectory = context.projectRoot();
    if (command == null || command.isBlank())
      throw new IllegalArgumentException("command must not be blank");
    if (workingDirectory == null || !workingDirectory.isAbsolute()) {
      throw new IllegalArgumentException("workingDirectory must be absolute");
    }
    if (timeout == null || timeout.isZero() || timeout.isNegative()) {
      throw new IllegalArgumentException("timeout must be positive");
    }
    if (cancellation == null) throw new IllegalArgumentException("cancellation must not be null");
    if (cancellation.isCancelled()) return new ScriptResult("", "", -1, false, true, false);

    Path root = workingDirectory.toAbsolutePath().normalize();
    SandboxedProcess prepared = sandbox.prepare(sandboxRequest(command, root, context, List.of()));
    protectUncontainedLaunch(command, context, sandbox);
    ProcessBuilder builder =
        new ProcessBuilder(prepared.argv()).directory(prepared.workingDirectory().toFile());
    applyEnvironment(builder.environment(), context);
    Process process = builder.start();
    var stdout = new OutputCollector(MAX_OUTPUT_CHARS);
    var stderr = new OutputCollector(MAX_OUTPUT_CHARS);
    Thread outReader =
        Thread.startVirtualThread(() -> readOutput(process.getInputStream(), stdout));
    Thread errorReader =
        Thread.startVirtualThread(() -> readOutput(process.getErrorStream(), stderr));
    Thread inputWriter =
        Thread.startVirtualThread(
            () -> {
              try (OutputStream stream = process.getOutputStream()) {
                stream.write(input == null ? new byte[0] : input.getBytes(StandardCharsets.UTF_8));
                stream.write('\n');
              } catch (IOException ignored) {
                // 超时、取消或进程提前退出时关闭 stdin 属于正常清理路径。
              }
            });

    ProcessWait completed = waitForProcess(process, context);
    boolean timedOut = completed.timedOut();
    boolean cancelled = completed.cancelled();
    joinReader(inputWriter);
    joinReader(outReader);
    joinReader(errorReader);
    requireReadersStopped(context, inputWriter, outReader, errorReader);
    int exitCode = process.isAlive() ? -1 : process.exitValue();
    return new ScriptResult(
        stdout.text(),
        stderr.text(),
        exitCode,
        timedOut,
        cancelled,
        stdout.truncated() || stderr.truncated());
  }

  private static void requireReadersStopped(ToolExecutionContext context, Thread... readers)
      throws IOException {
    for (Thread reader : readers)
      if (reader.isAlive()) {
        if (context.workspaceScope() != null) context.workspaceScope().markUnconfirmedProcess();
        throw new UnconfirmedProcessException();
      }
  }

  private BashSandboxRequest sandboxRequest(
      String command, Path cwd, ToolExecutionContext context, List<Path> readableAssets) {
    return context.workspaceScope() == null
        ? new BashSandboxRequest(command, cwd, List.of(context.projectRoot()))
        : context.workspaceScope().sandboxRequest(command, cwd, readableAssets);
  }

  private static void applyEnvironment(
      Map<String, String> environment, ToolExecutionContext context) {
    if (context.workspaceScope() != null) {
      if (context.workspaceScope().isolated()) {
        // 禁止 shell 启动脚本和导出的函数覆盖内建命令，保持保守判断的前提。
        environment
            .keySet()
            .removeIf(
                key ->
                    key.equals("BASH_ENV")
                        || key.equals("ENV")
                        || key.equals("SHELLOPTS")
                        || key.equals("BASHOPTS")
                        || key.startsWith("BASH_FUNC_"));
      }
      context.workspaceScope().applyEnvironment(environment);
    }
  }

  /** Linux 使用独立 PID 命名空间；其他平台对可能启动不透明进程的调用保守保留。 */
  private static void protectUncontainedLaunch(
      String command, ToolExecutionContext context, BashSandbox sandbox) {
    if (context.workspaceScope() == null
        || !context.workspaceScope().isolated()
        || sandbox instanceof com.mewcode.permission.LinuxBubblewrapSandbox) return;
    // 只豁免这几个完整的无参数命令；不解析 shell 语法或推测脚本会否派生进程。
    boolean builtinsOnly = command != null && Set.of("true", "false", "pwd", ":").contains(command);
    if (!builtinsOnly) context.workspaceScope().markUnconfirmedProcess();
  }

  private record ProcessWait(boolean timedOut, boolean cancelled) {}

  /** 记录本次进程的后代，取消后等待真实退出，不能仅返回 destroyForcibly 已发出。 */
  private static ProcessWait waitForProcess(Process process, ToolExecutionContext context)
      throws IOException {
    try {
      return waitForProcess(process, context.timeout(), context.cancellationToken());
    } catch (UnconfirmedProcessException error) {
      if (context.workspaceScope() != null) context.workspaceScope().markUnconfirmedProcess();
      throw error;
    }
  }

  private static final class UnconfirmedProcessException extends IOException {
    UnconfirmedProcessException() {
      super("无法确认命令进程实际停止，必须保留工作树");
    }
  }

  private static ProcessWait waitForProcess(
      Process process, java.time.Duration timeout, CancellationToken cancellation)
      throws IOException {
    long deadline = System.nanoTime() + timeout.toNanos();
    var descendants = new java.util.LinkedHashSet<ProcessHandle>();
    try {
      while (process.isAlive()) {
        process.descendants().forEach(descendants::add);
        if (cancellation.isCancelled() || System.nanoTime() >= deadline) {
          stopProcess(process, descendants);
          return new ProcessWait(!cancellation.isCancelled(), cancellation.isCancelled());
        }
        process.waitFor(25, TimeUnit.MILLISECONDS);
      }
      // shell 已返回但已观察到的后台进程未结束，不允许释放目录后继续写入。
      if (descendants.stream().anyMatch(ProcessHandle::isAlive)) stopProcess(process, descendants);
      return new ProcessWait(false, false);
    } catch (InterruptedException error) {
      stopProcess(process, descendants);
      Thread.currentThread().interrupt();
      throw new IOException("命令已中断，所启动进程已停止");
    }
  }

  private static void stopProcess(Process process, java.util.Set<ProcessHandle> known)
      throws IOException {
    process.descendants().forEach(known::add);
    new java.util.ArrayList<>(known).reversed().forEach(ProcessHandle::destroyForcibly);
    try {
      process.waitFor(200, TimeUnit.MILLISECONDS);
    } catch (InterruptedException ignored) {
    }
    if (process.isAlive()) process.destroyForcibly();
    long deadline = System.nanoTime() + java.time.Duration.ofSeconds(2).toNanos();
    while ((process.isAlive() || known.stream().anyMatch(ProcessHandle::isAlive))
        && System.nanoTime() < deadline)
      java.util.concurrent.locks.LockSupport.parkNanos(10_000_000);
    if (process.isAlive() || known.stream().anyMatch(ProcessHandle::isAlive))
      throw new UnconfirmedProcessException();
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
