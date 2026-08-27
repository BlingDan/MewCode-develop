package com.mewcode.permission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mewcode.agent.CancellationToken;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class PermissionBrokerTest {
  @Test
  void publishesAndResolvesARequestById() throws Exception {
    var broker = new PermissionBroker();
    var published = new CountDownLatch(1);
    var requestRef = new AtomicReference<PermissionRequest>();
    broker.setPublisher(
        request -> {
          requestRef.set(request);
          published.countDown();
        });
    PermissionRequest request =
        new PermissionRequest(
            "request-1",
            "Bash",
            Map.of("command", "git status"),
            "[Bash] git status",
            "当前模式要求确认",
            "Bash(git status)");

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var result = executor.submit(() -> broker.await(request, new CancellationToken()));
      assertTrue(published.await(1, TimeUnit.SECONDS));
      assertEquals(request, requestRef.get());
      assertTrue(broker.resolve("request-1", PermissionResponse.ALLOW_SESSION));
      assertEquals(PermissionResponse.ALLOW_SESSION, result.get(1, TimeUnit.SECONDS));
      assertEquals(0, broker.pendingCount());
      assertFalse(broker.resolve("request-1", PermissionResponse.ALLOW_ONCE));
    }
  }

  @Test
  void cancellationEndsTheWaitAsARejection() throws Exception {
    var broker = new PermissionBroker();
    var published = new CountDownLatch(1);
    broker.setPublisher(request -> published.countDown());
    var token = new CancellationToken();
    PermissionRequest request =
        new PermissionRequest(
            "request-2",
            "WriteFile",
            Map.of("path", "/tmp/a"),
            "[WriteFile] /tmp/a",
            "需要确认",
            "WriteFile(/tmp/a)");

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var result = executor.submit(() -> broker.await(request, token));
      assertTrue(published.await(1, TimeUnit.SECONDS));
      token.cancel();
      assertEquals(PermissionResponse.DENY, result.get(1, TimeUnit.SECONDS));
      assertEquals(0, broker.pendingCount());
    }
  }
}
