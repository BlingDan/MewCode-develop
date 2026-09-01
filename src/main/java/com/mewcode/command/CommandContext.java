package com.mewcode.command;

import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/** 命令可用的窄幅运行时能力，避免命令实现依赖具体 TUI 和领域对象。 */
public record CommandContext(
    String args,
    String workDir,
    String model,
    UIController ui,
    Supplier<String> status,
    Consumer<String> compact,
    Supplier<String> sessionInfo,
    Supplier<List<String>> sessionList,
    Function<String, String> sessionResume,
    Supplier<String> memorySummary,
    Supplier<List<String>> memoryList,
    BiFunction<String, String, String> memoryAdd,
    Runnable memoryClear,
    Supplier<String> permissionSummary,
    Supplier<List<String>> permissionRules,
    Function<String, String> permissionMode,
    BiFunction<String, String, String> permissionAdd,
    Runnable permissionReset) {

  public interface UIController {
    void addSystemMessage(String text);

    void sendUserMessage(String text);

    boolean isPlanMode();

    void setPlanMode(boolean enabled);

    long getTokenCount();

    void refreshStatus();

    void startNewConversation();

    void requestConfirmation(String text, Runnable onConfirm);
  }
}
