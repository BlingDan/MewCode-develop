package com.mewcode.worktree;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 主会话与资源身份分开保存；读取失败不退回为空状态，写入采用同目录原子替换。 */
public final class WorktreeSessionStore {
  private static final ObjectMapper JSON = new ObjectMapper();

  enum State {
    INITIALIZING,
    READY,
    PARTIAL
  }

  static final class Resource {
    String recordId = UUID.randomUUID().toString();
    String slug;
    Path path;
    String branch;
    Path sourceCwd;
    String baseCommit;
    Path gitCommonDir;
    Path gitDir;
    boolean temporary;
    String createdBySessionId;
    String createdByAgentId;
    Instant createdAt;
    Instant lastUsedAt;
    State state = State.INITIALIZING;
    List<Path> sharedDirectories = List.of();
    String hooksPath = "";
    String hooksConfigurationMode = "NONE";
    String directoryPresent = "UNKNOWN";
    String branchPresent = "UNKNOWN";
    String lastError = "";
  }

  static void validateId(String id) {
    if (id == null || !id.matches("[A-Za-z0-9_-]{1,128}"))
      throw new IllegalArgumentException("无效会话标识");
  }

  public void save(Path repoRoot, WorktreeSession session) throws IOException {
    save(repoRoot, session, null);
  }

  void save(Path repoRoot, WorktreeSession session, String resourceId) throws IOException {
    ObjectNode node = JSON.createObjectNode();
    node.put("version", 1);
    node.put("original_cwd", session.originalCwd().toString());
    node.put("worktree_path", session.worktreePath().toString());
    node.put("worktree_name", session.worktreeName());
    node.put("worktree_branch", session.worktreeBranch());
    node.put("original_branch", session.originalBranch());
    node.put("original_head_commit", session.originalHeadCommit());
    node.put("session_id", session.sessionId());
    node.put("agent_id", session.agentId());
    node.put("creation_duration_ms", session.creationDurationMs());
    if (resourceId != null) node.put("resource_id", resourceId);
    atomicWrite(sessionPath(repoRoot, session.sessionId()), node);
  }

  String savedResourceId(Path repoRoot, String sessionId) throws IOException {
    try {
      String id = text(read(sessionPath(repoRoot, sessionId)), "resource_id");
      UUID.fromString(id);
      return id;
    } catch (RuntimeException error) {
      throw new IOException("保存现场资源身份无效");
    }
  }

  public Optional<WorktreeSession> load(Path repoRoot, String sessionId) throws IOException {
    Path path = sessionPath(repoRoot, sessionId);
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
    try {
      JsonNode node = read(path);
      requireVersion(node);
      var session =
          new WorktreeSession(
              absolute(node, "original_cwd"),
              absolute(node, "worktree_path"),
              text(node, "worktree_name"),
              text(node, "worktree_branch"),
              text(node, "original_branch"),
              text(node, "original_head_commit"),
              text(node, "session_id"),
              text(node, "agent_id"),
              node.required("creation_duration_ms").longValue());
      if (!session.sessionId().equals(sessionId)) throw new IOException("会话归属不符");
      return Optional.of(session);
    } catch (RuntimeException error) {
      throw new IOException("会话记录无效");
    }
  }

  public void clear(Path repoRoot, String sessionId) throws IOException {
    Files.deleteIfExists(sessionPath(repoRoot, sessionId));
  }

  void saveResource(Path repoRoot, Resource resource) throws IOException {
    validateResource(repoRoot, resource);
    ObjectNode node = JSON.createObjectNode();
    node.put("version", 1);
    node.put("record_id", resource.recordId);
    node.put("slug", resource.slug);
    node.put("path", resource.path.toString());
    node.put("branch", resource.branch);
    node.put("source_cwd", resource.sourceCwd.toString());
    node.put("base_commit", resource.baseCommit);
    node.put("git_common_dir", resource.gitCommonDir.toString());
    if (resource.gitDir != null) node.put("git_dir", resource.gitDir.toString());
    node.put("temporary", resource.temporary);
    node.put("created_by_session_id", resource.createdBySessionId);
    node.put("created_by_agent_id", resource.createdByAgentId);
    node.put("created_at", resource.createdAt.toString());
    node.put("last_used_at", resource.lastUsedAt.toString());
    node.put("state", resource.state.name());
    var shared = node.putArray("shared_directories");
    resource.sharedDirectories.forEach(p -> shared.add(p.toString()));
    node.put("hooks_path", resource.hooksPath);
    node.put("hooks_configuration_mode", resource.hooksConfigurationMode);
    node.put("directory_present", resource.directoryPresent);
    node.put("branch_present", resource.branchPresent);
    node.put("last_error", resource.lastError);
    atomicWrite(resourcePath(repoRoot, resource.slug), node);
  }

  Optional<Resource> loadResource(Path repoRoot, String slug) throws IOException {
    Path path = resourcePath(repoRoot, slug);
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
    try {
      JsonNode node = read(path);
      requireVersion(node);
      Resource result = new Resource();
      result.recordId = text(node, "record_id");
      result.slug = text(node, "slug");
      result.path = absolute(node, "path");
      result.branch = text(node, "branch");
      result.sourceCwd = absolute(node, "source_cwd");
      result.baseCommit = text(node, "base_commit");
      result.gitCommonDir = absolute(node, "git_common_dir");
      result.gitDir = node.has("git_dir") ? absolute(node, "git_dir") : null;
      if (!node.required("temporary").isBoolean()) throw new IOException("归属无效");
      result.temporary = node.get("temporary").booleanValue();
      result.createdBySessionId = text(node, "created_by_session_id");
      result.createdByAgentId = text(node, "created_by_agent_id");
      result.createdAt = Instant.parse(text(node, "created_at"));
      result.lastUsedAt = Instant.parse(text(node, "last_used_at"));
      result.state = State.valueOf(text(node, "state"));
      var shared = new ArrayList<Path>();
      if (!node.required("shared_directories").isArray()) throw new IOException("共享路径无效");
      for (JsonNode entry : node.get("shared_directories")) {
        if (!entry.isTextual() || !Path.of(entry.textValue()).isAbsolute())
          throw new IOException("共享路径无效");
        shared.add(Path.of(entry.textValue()).normalize());
      }
      result.sharedDirectories = List.copyOf(shared);
      result.hooksPath = text(node, "hooks_path");
      result.hooksConfigurationMode = text(node, "hooks_configuration_mode");
      result.directoryPresent = text(node, "directory_present");
      result.branchPresent = text(node, "branch_present");
      result.lastError = text(node, "last_error");
      if (!slug.equals(result.slug)) throw new IOException("资源名称不符");
      validateResource(repoRoot, result);
      return Optional.of(result);
    } catch (RuntimeException error) {
      throw new IOException("资源记录无效");
    }
  }

  List<Resource> resources(Path repoRoot) throws IOException {
    Path directory = SlugValidator.safePath(repoRoot, ".mewcode/worktree-state/resources");
    if (!Files.exists(directory)) return List.of();
    var resources = new ArrayList<Resource>();
    try (var files = Files.list(directory)) {
      for (Path file : files.sorted().toList()) {
        if (!file.getFileName().toString().endsWith(".json")) continue;
        JsonNode node = read(file);
        resources.add(loadResource(repoRoot, text(node, "slug")).orElseThrow());
      }
    }
    return List.copyOf(resources);
  }

  List<Resource> cleanupCandidates(Path repoRoot, java.util.function.Consumer<String> diagnostics)
      throws IOException {
    Path directory = SlugValidator.safePath(repoRoot, ".mewcode/worktree-state/resources");
    if (!Files.exists(directory)) return List.of();
    var result = new ArrayList<Resource>();
    try (var files = Files.list(directory)) {
      for (Path file : files.sorted().toList()) {
        if (!file.getFileName().toString().endsWith(".json")) continue;
        try {
          var node = read(file);
          String slug = text(node, "slug");
          if (!file.equals(resourcePath(repoRoot, slug))) throw new IOException("资源文件归属不符");
          result.add(loadResource(repoRoot, slug).orElseThrow());
        } catch (IOException | RuntimeException error) {
          diagnostics.accept("过期扫描保留无法验证的资源记录。");
        }
      }
    }
    return List.copyOf(result);
  }

  boolean hasSavedSession(Path repoRoot, Path target) throws IOException {
    Path directory = SlugValidator.safePath(repoRoot, ".mewcode/worktree-state/sessions");
    if (!Files.exists(directory)) return false;
    try (var files = Files.list(directory)) {
      for (Path file : files.toList()) {
        if (!file.getFileName().toString().endsWith(".json")) continue;
        var session = load(repoRoot, text(read(file), "session_id")).orElseThrow();
        if (session.worktreePath().equals(target)) return true;
      }
    }
    return false;
  }

  void clearResource(Path repoRoot, String slug) throws IOException {
    Files.deleteIfExists(resourcePath(repoRoot, slug));
  }

  /** 全部为文件系统读取；必须同时验证正向、反向 Git 指针和独立分支。 */
  void verifyReady(Path repoRoot, Resource resource) throws IOException {
    validateResource(repoRoot, resource);
    if (resource.state != State.READY || resource.gitDir == null) throw new IOException("资源初始化未完成");
    Path target = resource.path;
    if (!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) throw new IOException("工作树目录缺失");
    Path pointer =
        SlugValidator.safePath(
            repoRoot, repoRoot.toRealPath().relativize(target.resolve(".git")).toString());
    String gitPointer = Files.readString(pointer).strip();
    if (!gitPointer.startsWith("gitdir: ")) throw new IOException("无效 Git 指针");
    Path actualGit = target.resolve(gitPointer.substring(8)).normalize().toRealPath();
    Path common = resource.gitCommonDir.toRealPath();
    if (!common.equals(commonDirectory(repoRoot))) throw new IOException("初始仓库归属不符");
    if (!actualGit.equals(resource.gitDir.toRealPath())
        || !actualGit.startsWith(common.resolve("worktrees")))
      throw new IOException("Git 管理目录归属不符");
    noLinks(common, actualGit);
    for (String file : List.of("commondir", "gitdir", "HEAD")) {
      noLinks(common, actualGit.resolve(file));
    }
    Path actualCommon =
        actualGit
            .resolve(Files.readString(actualGit.resolve("commondir")).strip())
            .normalize()
            .toRealPath();
    if (!actualCommon.equals(common)) throw new IOException("共享版本库不符");
    Path reverse =
        Path.of(Files.readString(actualGit.resolve("gitdir")).strip()).toAbsolutePath().normalize();
    if (!reverse.equals(pointer)) throw new IOException("反向 Git 指针不符");
    if (!Files.readString(actualGit.resolve("HEAD"))
        .strip()
        .equals("ref: refs/heads/" + resource.branch)) throw new IOException("资源分支不符");
    String commit = readRef(common, "refs/heads/" + resource.branch);
    if (!commit.matches("(?:[0-9a-f]{40}|[0-9a-f]{64})")) throw new IOException("分支引用无效");
    verifyObjectExists(common, resource.baseCommit);
    verifyObjectExists(common, commit);
    Path lock = lockPath(repoRoot, resource.slug);
    if (!Files.isRegularFile(lock, LinkOption.NOFOLLOW_LINKS)) throw new IOException("资源锁缺失");
  }

  /** 只读取对象目录或 pack 索引；恢复不创建进程，也不解包或修改对象。 */
  private static void verifyObjectExists(Path common, String hash) throws IOException {
    Path objects = common.resolve("objects");
    Path loose = objects.resolve(hash.substring(0, 2)).resolve(hash.substring(2));
    noLinks(common, loose);
    if (Files.isRegularFile(loose, LinkOption.NOFOLLOW_LINKS)) {
      try (var input = new java.util.zip.InflaterInputStream(Files.newInputStream(loose))) {
        var header = new java.io.ByteArrayOutputStream();
        int value;
        while ((value = input.read()) > 0 && header.size() < 64) header.write(value);
        if (value != 0
            || !header
                .toString(java.nio.charset.StandardCharsets.US_ASCII)
                .matches("commit [1-9][0-9]*")) throw new IOException("创建基线或分支对象无效");
        return;
      }
    }
    Path packs = objects.resolve("pack");
    noLinks(common, packs);
    if (Files.isDirectory(packs, LinkOption.NOFOLLOW_LINKS)) {
      try (var entries = Files.list(packs)) {
        for (Path index :
            entries.filter(path -> path.getFileName().toString().endsWith(".idx")).toList()) {
          noLinks(common, index);
          Path pack =
              index.resolveSibling(index.getFileName().toString().replaceFirst("\\.idx$", ".pack"));
          noLinks(common, pack);
          if (!Files.isRegularFile(pack, LinkOption.NOFOLLOW_LINKS)) continue;
          try (var channel =
              java.nio.channels.FileChannel.open(index, java.nio.file.StandardOpenOption.READ)) {
            var header = java.nio.ByteBuffer.allocate(8);
            if (channel.read(header, 0) != 8) continue;
            header.flip();
            if (header.getInt() != 0xff744f63 || header.getInt() != 2) continue;
            var lastCount = java.nio.ByteBuffer.allocate(4);
            if (channel.read(lastCount, 8 + 255 * 4) != 4) continue;
            lastCount.flip();
            long count = Integer.toUnsignedLong(lastCount.getInt());
            int width = hash.length() / 2;
            long table = 8 + 256 * 4;
            if (count > (channel.size() - table) / width) continue;
            byte[] target = java.util.HexFormat.of().parseHex(hash);
            long low = 0, high = count - 1;
            while (low <= high) {
              long mid = (low + high) >>> 1;
              var oid = java.nio.ByteBuffer.allocate(width);
              if (channel.read(oid, table + mid * width) != width)
                throw new IOException("pack 索引不完整");
              int compare = java.util.Arrays.compareUnsigned(oid.array(), target);
              if (compare == 0) return;
              if (compare < 0) low = mid + 1;
              else high = mid - 1;
            }
          }
        }
      }
    }
    throw new IOException("创建基线或分支对象缺失，无法验证恢复");
  }

  static Path commonDirectory(Path root) throws IOException {
    Path pointer = root.resolve(".git");
    if (Files.isSymbolicLink(pointer)) throw new IOException("Git 指针含符号链接");
    if (Files.isDirectory(pointer, LinkOption.NOFOLLOW_LINKS)) return pointer.toRealPath();
    String content = Files.readString(pointer).strip();
    if (!content.startsWith("gitdir: ")) throw new IOException("初始 Git 指针无效");
    Path directory = root.resolve(content.substring(8)).normalize().toRealPath();
    if (Files.isSymbolicLink(directory.resolve("commondir"))) throw new IOException("共享指针含符号链接");
    return directory
        .resolve(Files.readString(directory.resolve("commondir")).strip())
        .normalize()
        .toRealPath();
  }

  static String readRef(Path common, String ref) throws IOException {
    Path loose = common.resolve(ref).normalize();
    noLinks(common, loose);
    if (Files.isRegularFile(loose, LinkOption.NOFOLLOW_LINKS))
      return Files.readString(loose).strip();
    Path packed = common.resolve("packed-refs");
    noLinks(common, packed);
    if (Files.isRegularFile(packed, LinkOption.NOFOLLOW_LINKS)) {
      for (String line : Files.readAllLines(packed)) {
        String[] pair = line.split(" ", 2);
        if (pair.length == 2 && pair[1].equals(ref)) return pair[0];
      }
    }
    throw new IOException("分支引用缺失");
  }

  static void noLinks(Path base, Path target) throws IOException {
    if (!target.normalize().startsWith(base)) throw new IOException("Git 路径越界");
    Path cursor = base;
    for (Path part : base.relativize(target)) {
      cursor = cursor.resolve(part);
      if (Files.isSymbolicLink(cursor)) throw new IOException("Git 管理路径含符号链接");
    }
  }

  private static void validateResource(Path repoRoot, Resource r) throws IOException {
    try {
      UUID.fromString(r.recordId);
      validateId(r.createdBySessionId);
      validateId(r.createdByAgentId);
      if (!r.path.equals(
              SlugValidator.safePath(
                  repoRoot, ".mewcode/worktrees/" + SlugValidator.validate(r.slug)))
          || !r.branch.equals(SlugValidator.branch(r.slug))
          || !r.sourceCwd.isAbsolute()
          || !r.baseCommit.matches("(?:[0-9a-f]{40}|[0-9a-f]{64})")
          || !r.gitCommonDir.isAbsolute()
          || r.createdAt == null
          || r.lastUsedAt == null) throw new IOException("资源身份不符");
    } catch (IllegalArgumentException | NullPointerException error) {
      throw new IOException("资源身份无效");
    }
  }

  static Path lockPath(Path root, String slug) throws IOException {
    return SlugValidator.safePath(
        root, ".mewcode/worktree-state/locks/" + SlugValidator.validate(slug) + ".lock");
  }

  private Path resourcePath(Path root, String slug) throws IOException {
    return SlugValidator.safePath(
        root, ".mewcode/worktree-state/resources/" + SlugValidator.validate(slug) + ".json");
  }

  private Path sessionPath(Path root, String id) throws IOException {
    validateId(id);
    return SlugValidator.safePath(root, ".mewcode/worktree-state/sessions/" + id + ".json");
  }

  private static JsonNode read(Path path) throws IOException {
    if (Files.isSymbolicLink(path)
        || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
        || Files.size(path) > 1_048_576) throw new IOException("状态文件不可读或不可信");
    return JSON.readTree(Files.readString(path));
  }

  private static void requireVersion(JsonNode node) throws IOException {
    if (node == null
        || !node.path("version").isIntegralNumber()
        || node.get("version").intValue() != 1) throw new IOException("不支持的状态版本");
  }

  private static String text(JsonNode node, String field) throws IOException {
    JsonNode value = node.required(field);
    if (!value.isTextual()) throw new IOException("状态字段类型无效");
    return value.textValue();
  }

  private static Path absolute(JsonNode node, String field) throws IOException {
    Path path = Path.of(text(node, field));
    if (!path.isAbsolute()) throw new IOException("状态路径不是绝对路径");
    return path.normalize();
  }

  private static void atomicWrite(Path target, JsonNode node) throws IOException {
    Files.createDirectories(target.getParent());
    if (Files.isSymbolicLink(target)) throw new IOException("状态目标被符号链接替换");
    Path temporary = Files.createTempFile(target.getParent(), ".write-", ".tmp");
    try {
      try {
        Files.setPosixFilePermissions(
            temporary, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
      } catch (UnsupportedOperationException ignored) {
      }
      Files.writeString(
          temporary,
          JSON.writerWithDefaultPrettyPrinter().writeValueAsString(node),
          StandardOpenOption.TRUNCATE_EXISTING);
      Files.move(
          temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(temporary);
    }
  }
}
