package com.mewcode.hook;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 一个会话独享的 Hook once 状态和待发送 reminder 队列。 */
public final class HookSessionState implements AutoCloseable {
  private final Object lock = new Object();
  private final Set<String> executed = new HashSet<>();
  private final Set<String> running = new HashSet<>();
  private final List<PendingPrompt> prompts = new ArrayList<>();
  private long sequence;
  private boolean closed;

  public boolean tryStartOnce(String hookName) {
    if (hookName == null || hookName.isBlank()) return false;
    synchronized (lock) {
      if (closed || executed.contains(hookName) || !running.add(hookName)) return false;
      return true;
    }
  }

  public void markExecuted(String hookName) {
    if (hookName == null || hookName.isBlank()) return;
    synchronized (lock) {
      running.remove(hookName);
      executed.add(hookName);
    }
  }

  void releaseOnceStart(String hookName) {
    synchronized (lock) {
      running.remove(hookName);
    }
  }

  public void enqueuePrompt(String text) {
    if (text == null || text.isBlank()) return;
    synchronized (lock) {
      if (!closed) prompts.add(new PendingPrompt(++sequence, text));
    }
  }

  public ReminderBatch snapshotPrompts() {
    synchronized (lock) {
      long through = prompts.isEmpty() ? sequence : prompts.getLast().sequence();
      return new ReminderBatch(through, prompts.stream().map(PendingPrompt::text).toList());
    }
  }

  public void consumePrompts(ReminderBatch batch) {
    if (batch == null) return;
    synchronized (lock) {
      prompts.removeIf(prompt -> prompt.sequence() <= batch.throughSequence());
    }
  }

  public boolean isClosed() {
    synchronized (lock) {
      return closed;
    }
  }

  @Override
  public void close() {
    synchronized (lock) {
      if (closed) return;
      closed = true;
      prompts.clear();
      running.clear();
    }
  }

  public record ReminderBatch(long throughSequence, List<String> texts) {
    public ReminderBatch {
      texts = List.copyOf(texts == null ? List.of() : texts);
    }
  }

  private record PendingPrompt(long sequence, String text) {}
}
