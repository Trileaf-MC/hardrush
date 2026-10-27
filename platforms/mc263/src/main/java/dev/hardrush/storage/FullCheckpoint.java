package dev.hardrush.storage;

import dev.hardrush.core.round.RoundPhase;
import dev.hardrush.core.round.RoundSnapshot;
import dev.hardrush.core.round.ParticipantRole;
import dev.hardrush.persistence.SqliteRoundStore;
import dev.hardrush.world.RoundWorldPaths;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

/** Immutable full save points. Restore runs before formal worlds, profiles or SQLite are opened. */
public final class FullCheckpoint {
    public record Recovery(boolean restored, String message) {
        public static Recovery none() { return new Recovery(false, ""); }
    }
    private record Image(UUID id, RoundSnapshot round, long savedAt, Properties manifest, List<String> roots) {}
    public record Candidate(UUID id, RoundSnapshot round, long savedAt) {}
    public record Search(List<Candidate> candidates, List<String> failures) {
        public Search { candidates = List.copyOf(candidates); failures = List.copyOf(failures); }
    }
    private final Path root;
    private final Path directory;

    public FullCheckpoint(Path root) {
        this.root = root.toAbsolutePath().normalize();
        directory = this.root.resolve("hardrush/full-checkpoints");
    }

    public Path begin() throws IOException {
        checked(directory, true);
        Files.createDirectories(directory);
        Path pending = directory.resolve(UUID.randomUUID().toString());
        Files.createDirectory(pending);
        Files.createDirectory(pending.resolve("data"));
        return pending;
    }

    public Path database(Path pending) throws IOException {
        requireImagePath(pending);
        Path path = pending.resolve("data/" + dev.hardrush.storage.RoundStoragePaths.DATABASE);
        Files.createDirectories(path.getParent());
        return path;
    }

    public void discardIncomplete(Path pending) throws IOException {
        requireImagePath(pending);
        if (Files.exists(pending.resolve("complete.properties"), LinkOption.NOFOLLOW_LINKS)) return;
        try (var entries = Files.walk(pending)) {
            for (Path path : entries.sorted(java.util.Comparator.reverseOrder()).toList()) {
                if (Files.isSymbolicLink(path)) throw new IOException("拒绝清理含链接的不完整保存点");
                Files.delete(path);
            }
        }
    }

    /** Caller holds the server thread after native flushes and an idle SQLite export. */
    public void publish(Path pending, RoundSnapshot round, long deadlineNanos) throws Exception {
        publish(pending, round, deadlineNanos, java.util.Map.of());
    }

    public void publish(Path pending, RoundSnapshot round, long deadlineNanos, java.util.Map<UUID, byte[]> liveBodies) throws Exception {
        requireImagePath(pending);
        if (round.phase() != RoundPhase.RUNNING || round.result() != null) throw new IOException("保存点必须属于进行中的对局");
        var manifest = new Properties();
        manifest.setProperty("format", "1");
        manifest.setProperty("round", round.roundId().toString());
        manifest.setProperty("sequence", Long.toString(round.sequence()));
        manifest.setProperty("version", Long.toString(round.version()));
        manifest.setProperty("savedAt", Long.toString(System.currentTimeMillis()));
        List<String> roots = roots(round);
        int count = 0;
        for (String name : roots) {
            Path source = root.resolve(name);
            Path target = pending.resolve("data").resolve(name);
            if (!name.equals(dev.hardrush.storage.RoundStoragePaths.DATABASE)) {
                if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) throw new IOException("完整保存点缺少 " + name);
                copyTree(source, target, deadlineNanos);
            }
            if (name.equals("hardrush/profiles")) {
                var journal = new dev.hardrush.player.ProfileJournal(target);
                for (var body : liveBodies.entrySet()) journal.suspend(body.getKey(),
                        new dev.hardrush.player.ProfileJournal.Generation(round.roundId(), round.sequence()), body.getValue());
            }
            try (var entries = Files.walk(target)) {
                for (Path file : entries.sorted().toList()) {
                    if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) continue;
                    checkDeadline(deadlineNanos);
                    requireRegular(file);
                    String key = "file." + count++;
                    manifest.setProperty(key + ".path", portable(pending.resolve("data").relativize(file)));
                    manifest.setProperty(key + ".size", Long.toString(Files.size(file)));
                    manifest.setProperty(key + ".sha256", digest(file));
                }
            }
        }
        var exported = SqliteRoundStore.inspectLatest(database(pending)).orElseThrow();
        if (!exported.equals(round)) throw new IOException("世界保存点与 SQLite 快照不一致");
        manifest.setProperty("files", Integer.toString(count));
        var bytes = new java.io.ByteArrayOutputStream();
        manifest.store(bytes, "HardRush complete checkpoint");
        checkDeadline(deadlineNanos);
        atomicWrite(pending.resolve("complete.properties"), bytes.toByteArray());
        String previous = null;
        if (Files.exists(directory.resolve("latest"))) {
            try { previous = readPointer(directory.resolve("latest")).toString(); }
            catch (IOException ignored) { /* A new complete image can replace a damaged pointer. */ }
        }
        atomicWrite(directory.resolve("latest"), (pending.getFileName() + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        // Only generated checkpoint copies are retired; source worlds, archives and crash originals are retained.
        prune(pending.getFileName().toString(), previous);
    }

    public Recovery restore() {
        try {
            checked(directory, true);
            if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return Recovery.none();
            Path transaction = directory.resolve("restoring");
            boolean interrupted = Files.exists(transaction, LinkOption.NOFOLLOW_LINKS);
            RoundSnapshot current = null;
            if (!interrupted) {
                Path database = root.resolve(dev.hardrush.storage.RoundStoragePaths.DATABASE);
                if (!Files.exists(database, LinkOption.NOFOLLOW_LINKS)) {
                    return Files.exists(directory.resolve("latest"))
                            ? new Recovery(false, "比赛数据库缺失，已保留保存点并关闭参赛入口") : Recovery.none();
                }
                current = SqliteRoundStore.inspectLatest(database).orElse(null);
                if (current == null || current.cleanShutdown()) return Recovery.none();
                if (current.phase() != RoundPhase.RUNNING || current.result() != null) {
                    return new Recovery(false, "异常退出时对局不处于可续玩的进行中阶段，已保留原状态等待管理员处理");
                }
                if (!Files.exists(directory.resolve("latest"))) return new Recovery(false, "崩溃前尚未形成完整保存点，无法自动续局");
            }
            UUID id = readPointer(interrupted ? transaction : directory.resolve("latest"));
            Image image = verify(id);
            if (current != null && (!current.roundId().equals(image.round().roundId())
                    || current.sequence() != image.round().sequence() || current.version() < image.round().version()
                    || !current.rules().equals(image.round().rules()))) {
                return new Recovery(false, "完整保存点不属于当前对局或规则已改变，禁止跨轮回退");
            }
            if (current != null && current.participants().values().stream().anyMatch(player -> retainIdentity(player.role())
                    && (image.round().participants().get(player.id()) == null
                        || image.round().participants().get(player.id()).role() != player.role()))) {
                return new Recovery(false, "完整保存点早于玩家身份转换、退出或资格取消决策，已保留最新身份并等待处理");
            }
            apply(image);
            return new Recovery(true, "已从完整保存点恢复本轮（" + java.time.Instant.ofEpochMilli(image.savedAt())
                    + "）；停服期间不计时，成绩标记待核验");
        } catch (Exception error) {
            return new Recovery(false, "完整保存点恢复失败，留在大厅等待处理：" + error.getMessage());
        }
    }

    /** Read-only online preflight, independent of the latest pointer. Never opens a live writable database. */
    public Search search(RoundSnapshot current, java.util.Set<UUID> rejected) throws Exception {
        checked(directory, true);
        var candidates = new ArrayList<Candidate>();
        var failures = new ArrayList<String>();
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return new Search(candidates, List.of("没有完整保存点"));
        RoundSnapshot durable = onlineDurableFrontier();
        try (var paths = Files.list(directory)) {
            for (Path path : paths.toList()) {
                UUID id;
                try { id = canonicalUuid(path.getFileName().toString()); }
                catch (Exception ignored) { continue; }
                if (rejected.contains(id)) continue;
                try {
                    Image image = verify(id);
                    requireCompatible(current, image.round());
                    if (durable != null) requireCompatible(durable, image.round());
                    candidates.add(new Candidate(id, image.round(), image.savedAt()));
                } catch (Exception error) { failures.add(id + ": " + error.getMessage()); }
            }
        }
        candidates.sort(java.util.Comparator.comparingLong(Candidate::savedAt).reversed()
                .thenComparing(java.util.Comparator.comparingLong((Candidate c) -> c.round().version()).reversed()));
        return new Search(candidates, failures);
    }

    public void verifyCandidate(Candidate candidate, RoundSnapshot current) throws Exception {
        Image image = verify(candidate.id());
        if (!image.round().equals(candidate.round()) || image.savedAt() != candidate.savedAt()) throw new IOException("保存点在确认期间发生变化");
        requireCompatible(current, image.round());
        RoundSnapshot durable = onlineDurableFrontier();
        if (durable != null) requireCompatible(durable, image.round());
    }

    private RoundSnapshot onlineDurableFrontier() throws Exception {
        // An interrupted replacement has mixed live paths; the pre-attempt session remains the authority.
        // Otherwise read durable decisions too: a successful commit may have lost its callback.
        if (Files.exists(directory.resolve("restoring"), LinkOption.NOFOLLOW_LINKS)) return null;
        Path database = root.resolve(dev.hardrush.storage.RoundStoragePaths.DATABASE);
        return SqliteRoundStore.inspectLatest(database).orElseThrow(() -> new IOException("原数据库缺少可核验的轮次"));
    }

    /** Caller has evacuated players, drained profiles and closed all formal worlds and the live SQLite handle. */
    public void restoreCandidate(Candidate candidate, RoundSnapshot current) throws Exception {
        verifyCandidate(candidate, current);
        apply(verify(candidate.id()));
    }

    public void abandonRecoveryTransaction() throws IOException {
        Path marker = directory.resolve("restoring");
        checked(marker, false);
        if (Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) {
            requireRegular(marker);
            Files.move(marker, directory.resolve("abandoned-" + UUID.randomUUID()), StandardCopyOption.ATOMIC_MOVE);
            forceDirectory(directory);
        }
    }

    private static void requireCompatible(RoundSnapshot current, RoundSnapshot saved) throws IOException {
        if (current.result() != null || current.phase() != RoundPhase.RUNNING && current.phase() != RoundPhase.RECOVERY_REQUIRED) {
            throw new IOException("当前轮次已有终局或不支持续局，禁止回退");
        }
        if (!current.roundId().equals(saved.roundId()) || current.sequence() != saved.sequence()
                || current.seed() != saved.seed() || current.version() < saved.version() || !current.rules().equals(saved.rules())) {
            throw new IOException("保存点不属于当前轮次、版本或规则，禁止跨轮回退");
        }
        if (current.participants().values().stream().anyMatch(player -> retainIdentity(player.role())
                && (saved.participants().get(player.id()) == null || saved.participants().get(player.id()).role() != player.role()))) {
            throw new IOException("保存点早于玩家身份转换、退出或资格取消决策，不能撤销最新身份决定");
        }
    }

    /** Non-playable identities must survive rollback, including roles supplied by other identity adapters. */
    private static boolean retainIdentity(ParticipantRole role) {
        return role != ParticipantRole.LOBBY && role != ParticipantRole.HUMAN_ACTIVE
                && role != ParticipantRole.HUMAN_RESPAWNING && !role.isMonster();
    }

    private Image verify(UUID id) throws Exception {
        Path saved = directory.resolve(id.toString());
        requireImagePath(saved);
        Path file = saved.resolve("complete.properties");
        requireRegular(file);
        if (Files.size(file) > 32L * 1024 * 1024) throw new IOException("保存点清单过大");
        var manifest = new Properties();
        try (var in = Files.newInputStream(file)) { manifest.load(in); }
        if (!"1".equals(manifest.getProperty("format"))) throw new IOException("未知保存点格式");
        UUID roundId = canonicalUuid(manifest.getProperty("round"));
        long sequence = Long.parseLong(manifest.getProperty("sequence"));
        long version = Long.parseLong(manifest.getProperty("version"));
        long savedAt = Long.parseLong(manifest.getProperty("savedAt"));
        // Do not open an unverified SQLite image: check every byte first.
        var expected = new LinkedHashSet<String>();
        int count = Integer.parseInt(manifest.getProperty("files"));
        if (count < 1 || count > 200_000) throw new IOException("保存点文件数量非法");
        var allowed = rootNames(roundId);
        for (int i = 0; i < count; i++) {
            String key = "file." + i;
            String name = manifest.getProperty(key + ".path");
            requireRelative(name, allowed);
            if (!expected.add(name)) throw new IOException("保存点文件重复");
            Path source = saved.resolve("data").resolve(name);
            checked(source, false);
            requireRegular(source);
            if (Files.size(source) != Long.parseLong(manifest.getProperty(key + ".size"))
                    || !digest(source).equals(manifest.getProperty(key + ".sha256"))) throw new IOException("保存点文件损坏或缺失：" + name);
        }
        Path data = saved.resolve("data");
        checked(data, true);
        try (var paths = Files.walk(data)) {
            for (Path path : paths.toList()) {
                if (Files.isSymbolicLink(path)) throw new IOException("保存点包含符号链接");
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) continue;
                requireRegular(path);
                if (!expected.remove(portable(data.relativize(path)))) throw new IOException("保存点包含未登记文件");
            }
        }
        if (!expected.isEmpty()) throw new IOException("保存点不完整");
        var snapshot = SqliteRoundStore.inspectLatest(data.resolve(dev.hardrush.storage.RoundStoragePaths.DATABASE)).orElseThrow();
        if (!snapshot.roundId().equals(roundId) || snapshot.sequence() != sequence || snapshot.version() != version
                || snapshot.phase() != RoundPhase.RUNNING || snapshot.result() != null
                || dev.hardrush.core.round.RoundEngine.requiresLegacyClosure(snapshot)) throw new IOException("保存点数据库与清单不一致");
        for (String name : allowed) {
            Path source = data.resolve(name);
            checked(source, !name.endsWith(".json") && !name.endsWith(".sqlite"));
            if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) throw new IOException("保存点缺少必要目录：" + name);
        }
        return new Image(id, snapshot, savedAt, manifest, allowed);
    }

    private void apply(Image image) throws Exception {
        // Build every replacement first. A failed verification/copy cannot partially alter the live save.
        Path attempt = directory.resolve("restores").resolve(UUID.randomUUID().toString());
        checked(attempt, true);
        Files.createDirectories(attempt.resolve("replacement"));
        for (String name : image.roots()) copyTree(directory.resolve(image.id().toString()).resolve("data").resolve(name),
                attempt.resolve("replacement").resolve(name), Long.MAX_VALUE);
        atomicWrite(directory.resolve("restoring"), (image.id() + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var paths = new ArrayList<>(image.roots());
        // A standalone SQLite image must never meet the crashed database's old WAL/SHM/journal.
        paths.addFirst(dev.hardrush.storage.RoundStoragePaths.DATABASE + "-journal");
        paths.addFirst(dev.hardrush.storage.RoundStoragePaths.DATABASE + "-shm");
        paths.addFirst(dev.hardrush.storage.RoundStoragePaths.DATABASE + "-wal");
        for (String name : paths) checked(root.resolve(name), !name.endsWith(".json") && !name.contains(".sqlite"));
        for (String name : paths) {
            Path target = root.resolve(name);
            checked(target, !name.endsWith(".json") && !name.contains(".sqlite"));
            Path original = attempt.resolve("original").resolve(name);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                Files.createDirectories(original.getParent());
                Files.move(target, original, StandardCopyOption.ATOMIC_MOVE);
            }
            Path replacement = attempt.resolve("replacement").resolve(name);
            if (Files.exists(replacement, LinkOption.NOFOLLOW_LINKS)) {
                Files.createDirectories(target.getParent());
                Files.move(replacement, target, StandardCopyOption.ATOMIC_MOVE);
            }
            forceDirectory(target.getParent());
        }
        // If killed at any earlier swap, the next startup replays the same immutable image.
        Files.delete(directory.resolve("restoring"));
        forceDirectory(directory);
    }

    private List<String> roots(RoundSnapshot round) { return rootNames(round.roundId()); }
    private List<String> rootNames(UUID round) {
        var names = new ArrayList<String>();
        for (String dimension : List.of("overworld", "nether", "end")) names.add(portable(root.relativize(RoundWorldPaths.world(root, round, dimension))));
        names.add(portable(root.relativize(RoundWorldPaths.manifest(root, round))));
        names.add("hardrush/profiles");
        names.add("players");
        names.add(dev.hardrush.storage.RoundStoragePaths.DATABASE);
        return names;
    }

    private void requireImagePath(Path image) throws IOException {
        if (!directory.equals(image.toAbsolutePath().normalize().getParent())) throw new IOException("保存点路径越界");
        canonicalUuid(image.getFileName().toString());
        checked(image, true);
    }

    private static void requireRelative(String name, List<String> roots) throws IOException {
        if (name == null || name.contains("\\") || name.startsWith("/") || name.contains(":")) throw new IOException("非法保存点路径");
        Path path = Path.of(name);
        if (path.isAbsolute() || !portable(path.normalize()).equals(name)
                || roots.stream().noneMatch(base -> name.equals(base) || name.startsWith(base + "/"))) throw new IOException("保存点路径越界");
    }

    private static String portable(Path path) { return path.toString().replace('\\', '/'); }
    private static void checked(Path path, boolean directory) throws IOException { RoundWorldPaths.checkAncestors(path, directory); }
    private static UUID canonicalUuid(String value) throws IOException {
        try {
            UUID id = UUID.fromString(value);
            if (!id.toString().equals(value)) throw new IllegalArgumentException();
            return id;
        } catch (RuntimeException error) { throw new IOException("非法保存点标识", error); }
    }
    private static UUID readPointer(Path pointer) throws IOException {
        checked(pointer, false);
        requireRegular(pointer);
        if (Files.size(pointer) > 64) throw new IOException("保存点指针损坏");
        return canonicalUuid(Files.readString(pointer).strip());
    }
    private static void requireRegular(Path file) throws IOException {
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("保存点文件缺失或不是普通文件：" + file.getFileName());
    }
    private static void checkDeadline(long deadline) throws IOException {
        if (deadline != Long.MAX_VALUE && System.nanoTime() - deadline >= 0) throw new IOException("完整保存点复制超过时间预算；继续保留上一个完整保存点");
    }
    private static String digest(Path path) throws Exception {
        var hash = MessageDigest.getInstance("SHA-256");
        try (var in = Files.newInputStream(path)) {
            byte[] buffer = new byte[1024 * 1024];
            for (int n; (n = in.read(buffer)) != -1;) hash.update(buffer, 0, n);
        }
        return HexFormat.of().formatHex(hash.digest());
    }
    private static void copyTree(Path source, Path target, long deadline) throws IOException {
        checked(source, Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS));
        try (var walk = Files.walk(source)) {
            for (Path entry : walk.toList()) {
                checkDeadline(deadline);
                if (Files.isSymbolicLink(entry)) throw new IOException("保存目录包含符号链接：" + entry);
                Path out = target.resolve(source.relativize(entry));
                if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) Files.createDirectories(out);
                else {
                    requireRegular(entry);
                    Files.createDirectories(out.getParent());
                    try (var input = FileChannel.open(entry, StandardOpenOption.READ);
                         var output = FileChannel.open(out, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                        var buffer = ByteBuffer.allocate(1024 * 1024);
                        while (input.read(buffer) != -1) {
                            checkDeadline(deadline);
                            buffer.flip();
                            while (buffer.hasRemaining()) output.write(buffer);
                            buffer.clear();
                        }
                        output.force(true);
                    }
                }
            }
        }
    }
    private static void atomicWrite(Path path, byte[] bytes) throws IOException {
        checked(path, false);
        Files.createDirectories(path.getParent());
        Path temporary = Files.createTempFile(path.getParent(), ".checkpoint-", ".pending");
        try {
            try (var file = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                var buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) file.write(buffer);
                file.force(true);
            }
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            forceDirectory(path.getParent());
        } finally { Files.deleteIfExists(temporary); }
    }
    private static void forceDirectory(Path directory) throws IOException {
        // Windows does not expose directory handles through FileChannel; atomic moves remain required.
        if (System.getProperty("os.name", "").startsWith("Windows")) return;
        try (var channel = FileChannel.open(directory, StandardOpenOption.READ)) { channel.force(true); }
    }
    private void prune(String latest, String previous) throws IOException {
        try (var entries = Files.list(directory)) {
            for (Path candidate : entries.toList()) {
                String name = candidate.getFileName().toString();
                if (name.equals(latest) || name.equals(previous)) continue;
                try { canonicalUuid(name); } catch (IOException ignored) { continue; }
                checked(candidate, true);
                try (var tree = Files.walk(candidate)) {
                    for (Path path : tree.sorted(java.util.Comparator.reverseOrder()).toList()) {
                        if (Files.isSymbolicLink(path)) throw new IOException("拒绝清理含链接的保存点");
                        Files.delete(path);
                    }
                }
            }
        }
    }
}
