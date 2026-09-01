package com.mewcode.memory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.LinkOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** 管理一个 memory 目录及其 MEMORY.md 索引。 */
public final class MemoryStore {

    private static final String INDEX_FILE = "MEMORY.md";
    private static final Pattern SLUG = Pattern.compile("[a-z0-9]+(?:_[a-z0-9]+)*");
    private static final Pattern FILENAME = Pattern.compile("[a-z][a-z0-9_]*_[a-z0-9_]+\\.md");

    private final Path directory;
    private final MemoryLevel level;
    private final ReentrantLock lock = new ReentrantLock();

    public MemoryStore(Path directory, MemoryLevel level) {
        this.directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
        this.level = Objects.requireNonNull(level, "level");
    }

    public void ensureDirectory() throws IOException {
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)
                && !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("memory path is not a directory");
        }
        Files.createDirectories(directory);
    }

    public String loadIndex() throws IOException {
        lock.lock();
        try {
            Path index = directory.resolve(INDEX_FILE);
            return Files.isRegularFile(index, LinkOption.NOFOLLOW_LINKS)
                    ? Files.readString(index, StandardCharsets.UTF_8)
                    : "";
        } finally {
            lock.unlock();
        }
    }

    public List<MemoryNote> scanNotes() throws IOException {
        lock.lock();
        try {
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return List.of();
            var notes = new ArrayList<MemoryNote>();
            try (Stream<Path> paths = Files.list(directory)) {
                for (Path path : paths.sorted(Comparator.comparing(Path::toString)).toList()) {
                    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                            || INDEX_FILE.equals(path.getFileName().toString())) continue;
                    try {
                        MemoryNote note = parseNote(path, level);
                        if (note != null) notes.add(note);
                    } catch (RuntimeException ignored) {
                        // 单个手写坏笔记不应阻止其他 memory 注入。
                    }
                }
            }
            return List.copyOf(notes);
        } finally {
            lock.unlock();
        }
    }

    /** 获取当前目录的可回滚快照；只包含本 Store 管理的笔记和索引。 */
    Snapshot snapshot() throws IOException {
        lock.lock();
        try {
            var files = new LinkedHashMap<String, String>();
            if (Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                try (Stream<Path> paths = Files.list(directory)) {
                    for (Path path : paths.toList()) {
                        String filename = path.getFileName().toString();
                        if (!isManagedFilename(filename)
                                || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) continue;
                        files.put(filename, Files.readString(path, StandardCharsets.UTF_8));
                    }
                }
            }
            Path index = directory.resolve(INDEX_FILE);
            return new Snapshot(
                    new StagedMemory(files, loadIndexUnlocked(), Set.of()),
                    Files.exists(index, LinkOption.NOFOLLOW_LINKS));
        } finally {
            lock.unlock();
        }
    }

    /** 恢复快照并删除恢复前新增的本 Store 笔记。 */
    void restore(Snapshot snapshot) throws IOException {
        Objects.requireNonNull(snapshot, "snapshot");
        lock.lock();
        try {
            var removed = new LinkedHashSet<String>();
            if (Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                try (Stream<Path> paths = Files.list(directory)) {
                    for (Path path : paths.toList()) {
                        String filename = path.getFileName().toString();
                        if (isManagedFilename(filename)
                                && !snapshot.staged().noteFiles().containsKey(filename)) {
                            removed.add(filename);
                        }
                    }
                }
            }
            commit(new StagedMemory(snapshot.staged().noteFiles(), snapshot.staged().index(), removed));
            if (!snapshot.indexExists()) Files.deleteIfExists(directory.resolve(INDEX_FILE));
        } finally {
            lock.unlock();
        }
    }

    /** 根据一批已通过 JSON 解析的操作生成尚未落盘的快照。 */
    public StagedMemory stage(List<MemoryOperation> operations) throws IOException {
        lock.lock();
        try {
            Map<String, MemoryNote> notes = new LinkedHashMap<>();
            for (MemoryNote note : scanNotesUnlocked()) notes.put(note.filename(), note);
            Set<String> original = new LinkedHashSet<>(notes.keySet());
            for (MemoryOperation operation : operations == null ? List.<MemoryOperation>of() : operations) {
                applyOperation(notes, Objects.requireNonNull(operation, "operation"));
            }
            var files = new LinkedHashMap<String, String>();
            notes.values().stream()
                    .sorted(Comparator.comparing(MemoryNote::filename))
                    .forEach(note -> files.put(note.filename(), renderNote(note)));
            original.removeAll(files.keySet());
            return new StagedMemory(files, buildIndex(notes.values()), Set.copyOf(original));
        } finally {
            lock.unlock();
        }
    }

    /** 将已经校验并 staged 的笔记和索引写入当前目录。 */
    public void commit(StagedMemory staged) throws IOException {
        Objects.requireNonNull(staged, "staged");
        lock.lock();
        try {
            ensureDirectory();
            var noteTargets = new LinkedHashMap<String, Path>();
            for (String filename : staged.noteFiles().keySet()) {
                noteTargets.put(filename, safePath(filename));
            }
            var removedTargets = new LinkedHashMap<String, Path>();
            for (String filename : staged.removedFiles()) {
                removedTargets.put(filename, safePath(filename));
            }
            var targets = new LinkedHashSet<Path>();
            targets.addAll(noteTargets.values());
            targets.addAll(removedTargets.values());
            targets.add(directory.resolve(INDEX_FILE));
            if (!java.util.Collections.disjoint(noteTargets.keySet(), removedTargets.keySet())) {
                throw new IllegalArgumentException("memory 提交目标重复。");
            }

            var backups = new LinkedHashMap<Path, Path>();
            var applied = new LinkedHashSet<Path>();
            try {
                for (Path target : targets) {
                    if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) continue;
                    Path backup = Files.createTempFile(directory, ".memory-backup-", ".tmp");
                    try {
                        moveReplacing(target, backup);
                    } catch (IOException | RuntimeException error) {
                        Files.deleteIfExists(backup);
                        throw error;
                    }
                    backups.put(target, backup);
                }
                for (Map.Entry<String, String> entry : staged.noteFiles().entrySet()) {
                    writeAtomic(noteTargets.get(entry.getKey()), entry.getValue());
                    applied.add(noteTargets.get(entry.getKey()));
                }
                for (Path target : removedTargets.values()) {
                    Files.deleteIfExists(target);
                    applied.add(target);
                }
                writeAtomic(directory.resolve(INDEX_FILE), staged.index());
                applied.add(directory.resolve(INDEX_FILE));
                deleteBackups(backups.values());
            } catch (IOException | RuntimeException error) {
                rollback(applied, backups, error);
                throw error;
            }
        } finally {
            lock.unlock();
        }
    }

    /** 仅替换索引，用于索引裁剪；笔记文件不变。 */
    public void replaceIndex(String index) throws IOException {
        lock.lock();
        try {
            ensureDirectory();
            writeAtomic(directory.resolve(INDEX_FILE), index == null ? "" : index);
        } finally {
            lock.unlock();
        }
    }

    /** 提供给 memory LLM 的本地笔记快照，不写入普通会话。 */
    public String describeNotes() throws IOException {
        lock.lock();
        try {
            var result = new StringBuilder();
            for (MemoryNote note : scanNotesUnlocked()) {
                result.append("文件：").append(note.filename())
                        .append("\n类型：").append(note.type().wire())
                        .append("\n标题：").append(note.title())
                        .append("\n正文：\n").append(note.content())
                        .append("\n\n");
            }
            return result.toString();
        } finally {
            lock.unlock();
        }
    }

    public Path directory() {
        return directory;
    }

    private void applyOperation(Map<String, MemoryNote> notes, MemoryOperation operation) {
        if (!level.wire().equals(require(operation.level(), "level"))) {
            throw new IllegalArgumentException("memory level 与目标目录不匹配。");
        }
        String action = require(operation.action(), "action");
        switch (action) {
            case "create" -> create(notes, operation);
            case "update" -> update(notes, operation);
            case "delete" -> delete(notes, operation);
            default -> throw new IllegalArgumentException("非法 memory action。");
        }
    }

    private void create(Map<String, MemoryNote> notes, MemoryOperation operation) {
        MemoryType type = allowedType(operation.type());
        String slug = validSlug(operation.slug());
        String filename = type.wire() + "_" + slug + ".md";
        if (notes.containsKey(filename)) throw new IllegalArgumentException("memory 文件已存在。");
        String title = require(operation.title(), "title");
        String content = require(operation.content(), "content");
        Instant now = Instant.now();
        notes.put(filename, new MemoryNote(type, title, slug, content, filename, now, now));
    }

    private void update(Map<String, MemoryNote> notes, MemoryOperation operation) {
        String filename = validFilename(operation.filename());
        MemoryNote existing = notes.get(filename);
        if (existing == null) throw new IllegalArgumentException("待更新 memory 不存在。");
        if (operation.type() != null && !operation.type().isBlank()
                && !existing.type().wire().equals(operation.type())) {
            throw new IllegalArgumentException("memory type 与已有文件不匹配。");
        }
        String title = require(operation.title(), "title");
        String content = require(operation.content(), "content");
        notes.put(filename, new MemoryNote(
                existing.type(), title, existing.slug(), content, filename, existing.created(), Instant.now()));
    }

    private void delete(Map<String, MemoryNote> notes, MemoryOperation operation) {
        String filename = validFilename(operation.filename());
        if (notes.remove(filename) == null) throw new IllegalArgumentException("待删除 memory 不存在。");
    }

    private MemoryType allowedType(String wire) {
        MemoryType type = MemoryType.fromWire(require(wire, "type"));
        if (!isAllowedType(type, level)) throw new IllegalArgumentException("memory type 与 level 不匹配。");
        return type;
    }

    private static boolean isAllowedType(MemoryType type, MemoryLevel level) {
        return level == MemoryLevel.PROJECT
                ? type == MemoryType.PROJECT_KNOWLEDGE || type == MemoryType.REFERENCE_MATERIAL
                : type == MemoryType.USER_PREFERENCE || type == MemoryType.CORRECTION_FEEDBACK;
    }

    private static String validSlug(String value) {
        String slug = require(value, "slug");
        if (!SLUG.matcher(slug).matches()) throw new IllegalArgumentException("非法 memory slug。");
        return slug;
    }

    private static String validFilename(String value) {
        String filename = require(value, "filename");
        if (!FILENAME.matcher(filename).matches() || filename.contains("..")) {
            throw new IllegalArgumentException("非法 memory 文件名。");
        }
        MemoryType type = null;
        for (MemoryType candidate : MemoryType.values()) {
            if (filename.startsWith(candidate.wire() + "_")) {
                type = candidate;
                break;
            }
        }
        if (type == null) throw new IllegalArgumentException("非法 memory 文件名类型。");
        String slug = filename.substring(type.wire().length() + 1, filename.length() - 3);
        validSlug(slug);
        return filename;
    }

    private Path safePath(String filename) {
        String valid = validFilename(filename);
        Path path = directory.resolve(valid).normalize();
        if (!path.getParent().equals(directory)) throw new IllegalArgumentException("非法 memory 路径。");
        return path;
    }

    private List<MemoryNote> scanNotesUnlocked() throws IOException {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return List.of();
        var notes = new ArrayList<MemoryNote>();
        try (Stream<Path> paths = Files.list(directory)) {
            for (Path path : paths.sorted(Comparator.comparing(Path::toString)).toList()) {
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                        || INDEX_FILE.equals(path.getFileName().toString())) continue;
                try {
                    MemoryNote note = parseNote(path, level);
                    if (note != null) notes.add(note);
                } catch (RuntimeException ignored) {
                    // 坏笔记跳过，避免阻断同目录的其他笔记。
                }
            }
        }
        return notes;
    }

    private String loadIndexUnlocked() throws IOException {
        Path index = directory.resolve(INDEX_FILE);
        return Files.isRegularFile(index, LinkOption.NOFOLLOW_LINKS)
                ? Files.readString(index, StandardCharsets.UTF_8)
                : "";
    }

    private boolean isManagedFilename(String filename) {
        try {
            String valid = validFilename(filename);
            for (MemoryType type : MemoryType.values()) {
                if (valid.startsWith(type.wire() + "_")) {
                    return isAllowedType(type, level);
                }
            }
            return false;
        } catch (RuntimeException error) {
            return false;
        }
    }

    private static MemoryNote parseNote(Path path, MemoryLevel level) throws IOException {
        String filename = validFilename(path.getFileName().toString());
        String text = Files.readString(path, StandardCharsets.UTF_8);
        if (!text.startsWith("---")) return null;
        int end = text.indexOf("\n---", 3);
        if (end < 0) return null;
        String frontmatter = text.substring(3, end).strip();
        String body = text.substring(end + 4).replaceFirst("^\\r?\\n", "");
        var fields = new LinkedHashMap<String, String>();
        for (String line : frontmatter.split("\\R")) {
            int colon = line.indexOf(':');
            if (colon > 0) fields.put(line.substring(0, colon).strip(), unquote(line.substring(colon + 1).strip()));
        }
        MemoryType type = MemoryType.fromWire(fields.get("type"));
        if (!isAllowedType(type, level)) throw new IllegalArgumentException("memory type 与 level 不匹配。");
        String title = require(fields.get("title"), "title");
        Instant created = parseInstant(fields.get("created"));
        Instant updated = parseInstant(fields.get("updated"));
        String prefix = type.wire() + "_";
        if (!filename.startsWith(prefix)) throw new IllegalArgumentException("memory type 与文件名不匹配。");
        String slug = filename.substring(prefix.length(), filename.length() - 3);
        return new MemoryNote(type, title, slug, body, filename, created, updated);
    }

    private static Instant parseInstant(String value) {
        String input = require(value, "timestamp");
        try {
            return Instant.parse(input);
        } catch (DateTimeParseException ignored) {
            return OffsetDateTime.parse(input).toInstant();
        }
    }

    private static String renderNote(MemoryNote note) {
        return "---\n"
                + "type: " + note.type().wire() + "\n"
                + "title: " + scalar(note.title()) + "\n"
                + "created: " + note.created() + "\n"
                + "updated: " + note.updated() + "\n"
                + "---\n"
                + note.content();
    }

    private String buildIndex(Iterable<MemoryNote> values) {
        var result = new StringBuilder("# Memory\n\n");
        for (MemoryNote note : values) {
            String summary = note.content().replaceAll("\\s+", " ").strip();
            if (summary.length() > 180) summary = summary.substring(0, 180) + "…";
            result.append("- ").append(note.filename()).append(" — ").append(note.title());
            if (!summary.isEmpty()) result.append("：").append(summary);
            result.append('\n');
        }
        return result.toString();
    }

    private void writeAtomic(Path target, String content) throws IOException {
        Path temporary = Files.createTempFile(directory, ".memory-", ".tmp");
        try {
            Files.writeString(temporary, content == null ? "" : content, StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException error) {
            Files.deleteIfExists(temporary);
            throw error;
        }
    }

    private static void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(
                    source,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteBackups(Iterable<Path> backups) {
        for (Path backup : backups) {
            try {
                Files.deleteIfExists(backup);
            } catch (IOException ignored) {
                // 提交已经完成，残留的隐藏备份不影响新状态。
            }
        }
    }

    private static void rollback(
            Set<Path> applied, Map<Path, Path> backups, Throwable failure) {
        for (Path target : applied) {
            try {
                Files.deleteIfExists(target);
            } catch (IOException error) {
                failure.addSuppressed(error);
            }
        }
        for (Map.Entry<Path, Path> entry : backups.entrySet()) {
            try {
                moveReplacing(entry.getValue(), entry.getKey());
            } catch (IOException error) {
                failure.addSuppressed(error);
            }
        }
        deleteBackups(backups.values());
    }

    private static String scalar(String value) {
        return value == null ? "" : value.replaceAll("[\\r\\n]+", " ").strip();
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1).replace("\\\"", "\"");
        }
        return value;
    }

    private static String require(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("缺少 memory " + name + "。");
        return value.strip();
    }

    public record StagedMemory(Map<String, String> noteFiles, String index, Set<String> removedFiles) {
        public StagedMemory {
            noteFiles = noteFiles == null ? Map.of() : Map.copyOf(noteFiles);
            index = index == null ? "" : index;
            removedFiles = removedFiles == null ? Set.of() : Set.copyOf(removedFiles);
        }

        public StagedMemory withIndex(String replacement) {
            return new StagedMemory(noteFiles, replacement, removedFiles);
        }
    }

    record Snapshot(StagedMemory staged, boolean indexExists) {}
}
