package com.mewcode.permission;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.yaml.snakeyaml.Yaml;

/** 保存会话外可复用的显式授权 key；文件固定为项目本地权限文件。 */
public final class PathAuthorizationStore {
  private final Path file;
  private final Set<String> grants = new LinkedHashSet<>();

  public PathAuthorizationStore(Path projectRoot) {
    this.file = projectRoot.toAbsolutePath().normalize().resolve(".mewcode/permissions.local.yaml");
  }

  private PathAuthorizationStore(Path file, Set<String> grants) {
    this.file = file;
    this.grants.addAll(grants);
  }

  public static PathAuthorizationStore load(Path projectRoot) throws IOException {
    Path file = projectRoot.toAbsolutePath().normalize().resolve(".mewcode/permissions.local.yaml");
    if (!Files.exists(file)) return new PathAuthorizationStore(file, Set.of());
    if (!Files.isRegularFile(file)) throw new IOException("本地权限文件不是普通文件：" + file);
    Object parsed;
    try {
      parsed = new Yaml().load(Files.readString(file));
    } catch (RuntimeException error) {
      throw new IOException("本地权限文件 YAML 无效：" + file, error);
    }
    if (parsed == null) return new PathAuthorizationStore(file, Set.of());
    if (!(parsed instanceof Map<?, ?> map)) throw new IOException("本地权限文件必须是 YAML 对象：" + file);
    Set<String> grants = parseGrants(map.get("grants"));
    return new PathAuthorizationStore(file, grants);
  }

  public synchronized boolean isAuthorized(String authorizationKey) {
    return grants.contains(authorizationKey);
  }

  public synchronized Set<String> grants() {
    return Set.copyOf(grants);
  }

  /** 只有持久化成功后才把授权加入内存，避免写失败造成假授权。 */
  public synchronized void grantAlways(String authorizationKey) throws IOException {
    if (authorizationKey == null || authorizationKey.isBlank()) {
      throw new IllegalArgumentException("authorizationKey must not be blank");
    }
    var next = new LinkedHashSet<>(grants);
    next.add(authorizationKey);
    persist(next);
    grants.clear();
    grants.addAll(next);
  }

  private void persist(Set<String> next) throws IOException {
    Path parent = file.getParent();
    if (parent != null) Files.createDirectories(parent);
    Map<String, Object> document = new LinkedHashMap<>();
    document.put("grants", new ArrayList<>(next));
    Files.writeString(file, new Yaml().dump(document));
  }

  private static Set<String> parseGrants(Object value) throws IOException {
    if (value == null) return Set.of();
    if (!(value instanceof List<?> list)) throw new IOException("本地权限文件 grants 必须是列表");
    var grants = new LinkedHashSet<String>();
    for (Object item : list) {
      if (!(item instanceof String text) || text.isBlank()) {
        throw new IOException("本地权限文件 grants 只能包含非空字符串");
      }
      grants.add(text);
    }
    return grants;
  }
}
