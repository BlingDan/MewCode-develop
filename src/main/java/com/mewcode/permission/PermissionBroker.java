package com.mewcode.permission;

import com.mewcode.agent.CancellationToken;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** 在工具执行线程和 TUI 事件流之间桥接确认请求。 */
public final class PermissionBroker implements AutoCloseable {
  private final Map<String, CompletableFuture<PermissionResponse>> pending =
      new ConcurrentHashMap<>();
  private volatile Consumer<PermissionRequest> publisher = ignored -> {};

  public void setPublisher(Consumer<PermissionRequest> publisher) {
    this.publisher = Objects.requireNonNull(publisher, "publisher");
  }

  /** 发布请求并等待响应；取消、重复请求和中断都安全收口为拒绝。 */
  public PermissionResponse await(PermissionRequest request, CancellationToken token) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(token, "token");
    if (token.isCancelled()) return PermissionResponse.DENY;
    var future = new CompletableFuture<PermissionResponse>();
    if (pending.putIfAbsent(request.requestId(), future) != null) {
      return PermissionResponse.DENY;
    }
    try {
      publisher.accept(request);
      while (!token.isCancelled()) {
        try {
          return future.get(50, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException ignored) {
          // 定期检查 AgentRun 取消信号。
        } catch (java.util.concurrent.ExecutionException error) {
          return PermissionResponse.DENY;
        }
      }
      return PermissionResponse.DENY;
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      return PermissionResponse.DENY;
    } catch (RuntimeException error) {
      throw new IllegalStateException("无法发布权限确认请求", error);
    } finally {
      pending.remove(request.requestId(), future);
    }
  }

  /** 由 TUI 根据请求 ID 唤醒对应的工具等待线程。 */
  public boolean resolve(String requestId, PermissionResponse response) {
    if (requestId == null || response == null) return false;
    CompletableFuture<PermissionResponse> future = pending.remove(requestId);
    return future != null && future.complete(response);
  }

  public int pendingCount() {
    return pending.size();
  }

  @Override
  public void close() {
    pending.forEach((requestId, future) -> future.complete(PermissionResponse.DENY));
    pending.clear();
  }
}
