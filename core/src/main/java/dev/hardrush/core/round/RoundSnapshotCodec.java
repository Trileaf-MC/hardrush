package dev.hardrush.core.round;

import dev.hardrush.core.config.RulesConfig;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.UUID;

/** Bounded, deterministic format. Schema upgrades must be explicit, never Java object deserialization. */
public final class RoundSnapshotCodec {
    private static final int MAGIC = 0x48525331;
    private static final int SCHEMA = 12;
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final int MAX_PARTICIPANTS = 10_000;

    private RoundSnapshotCodec() {}

    public static byte[] encode(RoundSnapshot snapshot) {
        try {
            var bytes = new ByteArrayOutputStream();
            var out = new DataOutputStream(bytes);
            out.writeInt(MAGIC); out.writeInt(SCHEMA);
            writeUuid(out, snapshot.roundId());
            out.writeLong(snapshot.sequence()); out.writeLong(snapshot.seed()); out.writeLong(snapshot.version());
            writeText(out, snapshot.rules().encode());
            writeText(out, snapshot.phase().name()); writeText(out, snapshot.pauseReason().name());
            out.writeLong(snapshot.challengeNanos()); out.writeLong(snapshot.remainingPhaseNanos());
            out.writeBoolean(snapshot.cleanShutdown()); out.writeBoolean(snapshot.timingUncertain());
            if (snapshot.participants().size() > MAX_PARTICIPANTS) throw new IllegalArgumentException("Too many participants in snapshot");
            out.writeInt(snapshot.participants().size());
            for (var entry : snapshot.participants().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey()).toList()) {
                Participant p = entry.getValue();
                writeUuid(out, p.id()); writeText(out, p.name()); writeText(out, p.role().name());
                out.writeBoolean(p.online()); out.writeBoolean(p.ready()); out.writeLong(p.humanNanos());
                out.writeLong(p.joinedAtChallengeNanos()); out.writeInt(p.lifeSequence()); out.writeInt(p.revivalCharges());
                writeText(out, p.monsterRole().name()); writeText(out, p.monsterDimension().name());
                out.writeLong(p.monsterCooldownNanos()); out.writeInt(p.monsterDeploymentSequence());
                out.writeInt(p.monsterSkillReadyAtMillis().size());
                for (var skill : MonsterSkill.values()) {
                    Long readyAt = p.monsterSkillReadyAtMillis().get(skill);
                    if (readyAt != null) { writeText(out, skill.id()); out.writeLong(readyAt); }
                }
                writeWallet(out, p.wallet());
                out.writeLong(p.lifeHumanNanos());
            }
            out.writeBoolean(snapshot.result() != null);
            if (snapshot.result() != null) {
                writeText(out, snapshot.result().kind().name());
                out.writeBoolean(snapshot.result().winner() != null);
                if (snapshot.result().winner() != null) writeUuid(out, snapshot.result().winner());
                writeText(out, snapshot.result().reason());
            }
            if (snapshot.looseTotems().size() > LooseTotem.MAX_REGISTERED) throw new IllegalArgumentException("Too many loose totems in snapshot");
            out.writeInt(snapshot.looseTotems().size());
            for (var entry : snapshot.looseTotems().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey()).toList()) {
                LooseTotem totem = entry.getValue();
                writeUuid(out, entry.getKey()); writeUuid(out, totem.victim()); out.writeInt(totem.victimLife());
                writeText(out, totem.source().name());
                out.writeBoolean(totem.killer().isPresent());
                if (totem.killer().isPresent()) writeUuid(out, totem.killer().get());
                out.writeLong(totem.droppedAtChallengeNanos());
            }
            // Only lives that have shown some threat are written, after everything older schemas held.
            var threats = snapshot.participants().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey())
                    .filter(entry -> !entry.getValue().threat().equals(HumanThreat.NONE)).toList();
            out.writeInt(threats.size());
            for (var entry : threats) { writeUuid(out, entry.getKey()); writeThreat(out, entry.getValue().threat()); }
            out.flush();
            if (bytes.size() > MAX_BYTES) throw new IllegalArgumentException("Snapshot exceeds size limit");
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void writeThreat(DataOutputStream out, HumanThreat threat) throws IOException {
        out.writeInt(threat.lifeSequence()); out.writeInt(threat.monsterKills()); out.writeInt(threat.hunted());
        var milestones = threat.milestones().stream().sorted().toList();
        out.writeInt(milestones.size());
        for (String milestone : milestones) writeText(out, milestone);
        var hunters = threat.huntedAt().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey()).toList();
        out.writeInt(hunters.size());
        for (var hunter : hunters) { writeUuid(out, hunter.getKey()); out.writeLong(hunter.getValue()); }
    }

    private static HumanThreat readThreat(DataInputStream in) throws IOException {
        int life = in.readInt(), kills = in.readInt(), hunted = in.readInt(), count = in.readInt();
        if (count < 0 || count > Bounty.MILESTONES.size()) throw new IOException("Invalid threat milestone count");
        var milestones = new java.util.HashSet<String>();
        for (int i = 0; i < count; i++) {
            if (!milestones.add(readText(in, 256))) throw new IOException("Duplicate threat milestone");
        }
        int hunters = in.readInt();
        if (hunters < 0 || hunters > MAX_PARTICIPANTS) throw new IOException("Invalid threat hunter count");
        var huntedAt = new java.util.HashMap<UUID, Long>();
        for (int i = 0; i < hunters; i++) {
            if (huntedAt.put(readUuid(in), in.readLong()) != null) throw new IOException("Duplicate threat hunter");
        }
        try { return new HumanThreat(life, kills, hunted, milestones, huntedAt); }
        catch (IllegalArgumentException invalid) { throw new IOException("Invalid human threat", invalid); }
    }

    public static RoundSnapshot decode(byte[] bytes) {
        if (bytes == null || bytes.length > MAX_BYTES) throw new IllegalArgumentException("Invalid snapshot size");
        try {
            var in = new DataInputStream(new ByteArrayInputStream(bytes));
            if (in.readInt() != MAGIC) throw new IOException("Unsupported snapshot format");
            int schema = in.readInt();
            if (schema < 1 || schema > SCHEMA) throw new IOException("Unsupported snapshot schema");
            UUID id = readUuid(in);
            long sequence = in.readLong(), seed = in.readLong(), version = in.readLong();
            RulesConfig rules = RulesConfig.decode(readText(in, 131_072));
            RoundPhase phase = RoundPhase.valueOf(readText(in, 64));
            PauseReason pause = PauseReason.valueOf(readText(in, 64));
            long challenge = in.readLong(), remaining = in.readLong();
            boolean clean = in.readBoolean(), uncertain = in.readBoolean();
            int count = in.readInt();
            if (count < 0 || count > MAX_PARTICIPANTS) throw new IOException("Invalid participant count");
            var players = new LinkedHashMap<UUID, Participant>();
            for (int i = 0; i < count; i++) {
                UUID playerId = readUuid(in);
                String name = readText(in, 256);
                ParticipantRole role = ParticipantRole.valueOf(readText(in, 64));
                boolean online = in.readBoolean(), ready = in.readBoolean();
                long humanNanos = in.readLong(), joinedAt = in.readLong();
                int lifeSequence = in.readInt();
                int revivalCharges = schema >= 6 ? in.readInt() : (in.readBoolean() ? 1 : 0);
                // Schemas 2–8 recorded a spawn-protection exit flag; the protection no longer exists.
                if (schema >= 2 && schema <= 8) in.readBoolean();
                MonsterRole monsterRole = schema >= 3 ? MonsterRole.valueOf(readText(in, 64)) : MonsterRole.ZOMBIE;
                MonsterDimension monsterDimension = schema >= 3 ? MonsterDimension.valueOf(readText(in, 64)) : MonsterDimension.OVERWORLD;
                // Older snapshots did not admit monster outings. Grant no bypass of the first wait.
                long monsterCooldown = schema >= 3 ? in.readLong()
                        : role == ParticipantRole.MONSTER_LOBBY || role == ParticipantRole.MONSTER_DEPLOYED
                                ? RoundEngine.MONSTER_COOLDOWN_NANOS : 0;
                int deploymentSequence = schema >= 3 ? in.readInt() : 0;
                var skillCooldowns = new LinkedHashMap<MonsterSkill, Long>();
                if (schema >= 4) {
                    int skills = in.readInt();
                    if (skills < 0 || skills > MonsterSkill.values().length) throw new IOException("Invalid skill cooldown count");
                    for (int skillIndex = 0; skillIndex < skills; skillIndex++) {
                        var skill = MonsterSkill.fromId(readText(in, 128));
                        if (skillCooldowns.put(skill, in.readLong()) != null) throw new IOException("Duplicate skill cooldown");
                    }
                }
                // Rounds saved before the coin economy carry an empty wallet; their rules keep it disabled.
                MonsterWallet wallet = schema >= 7 ? readWallet(in, schema) : MonsterWallet.EMPTY;
                // Older lives start their survival bounty from the upgrade instead of inheriting cross-life time.
                long lifeHumanNanos = schema >= 7 ? in.readLong() : 0;
                Participant p = new Participant(playerId, name, role, online, ready,
                        humanNanos, joinedAt, lifeSequence, revivalCharges,
                        monsterRole, monsterDimension, monsterCooldown, deploymentSequence, skillCooldowns, wallet, lifeHumanNanos);
                if (players.put(playerId, p) != null) throw new IOException("Duplicate participant UUID");
            }
            RoundResult result = null;
            if (in.readBoolean()) {
                RoundResult.Kind kind = RoundResult.Kind.valueOf(readText(in, 64));
                UUID winner = in.readBoolean() ? readUuid(in) : null;
                result = new RoundResult(kind, winner, readText(in, 4096));
            }
            // Schemas 1–9 predate dropped revival totems: nothing lies in the world.
            var looseTotems = new LinkedHashMap<UUID, LooseTotem>();
            if (schema >= 10) {
                int loose = in.readInt();
                if (loose < 0 || loose > LooseTotem.MAX_REGISTERED) throw new IOException("Invalid loose totem count");
                for (int i = 0; i < loose; i++) {
                    UUID token = readUuid(in);
                    UUID victim = readUuid(in);
                    int victimLife = in.readInt();
                    // Schemas 10–11 only dropped totems at a human's attack, always naming the killer.
                    LooseTotem.Source source = schema >= 12 ? LooseTotem.Source.valueOf(readText(in, 64)) : LooseTotem.Source.HUMAN;
                    java.util.Optional<UUID> killer = schema < 12 || in.readBoolean() ? java.util.Optional.of(readUuid(in)) : java.util.Optional.empty();
                    var totem = new LooseTotem(victim, victimLife, source, killer, in.readLong());
                    if (looseTotems.put(token, totem) != null) throw new IOException("Duplicate loose totem token");
                }
            }
            // Schemas 1–10 predate relative bounties: every life starts with no threat.
            if (schema >= 11) {
                int threats = in.readInt();
                if (threats < 0 || threats > players.size()) throw new IOException("Invalid threat count");
                var seen = new java.util.HashSet<UUID>();
                for (int i = 0; i < threats; i++) {
                    UUID playerId = readUuid(in);
                    Participant p = players.get(playerId);
                    if (p == null || !seen.add(playerId)) throw new IOException("Threat for an unknown or repeated participant");
                    players.put(playerId, p.withThreat(readThreat(in)));
                }
            }
            if (in.available() != 0) throw new IOException("Trailing snapshot data");
            if (remaining < 0) throw new IOException("Invalid remaining phase time");
            // Schemas 1–4 stored the configured (formerly ten-minute) success timer.
            // Keep elapsed server time and immutable rules; v5 already stores the capped timer.
            if (schema < 5 && result != null && result.kind() == RoundResult.Kind.COMPLETED
                    && (phase == RoundPhase.SETTLING || phase == RoundPhase.CELEBRATING)) {
                long removedMillis = Math.max(0, rules.successCelebrationMillis() - RoundEngine.MAX_SUCCESS_CELEBRATION_MILLIS);
                remaining = Math.max(0, remaining - removedMillis * 1_000_000L);
            }
            return new RoundSnapshot(id, sequence, seed, version, rules, phase, pause, challenge, remaining,
                    players, clean, uncertain, result, looseTotems);
        } catch (IOException | IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Invalid round snapshot", invalid);
        }
    }

    private static void writeWallet(DataOutputStream out, MonsterWallet wallet) throws IOException {
        out.writeInt(wallet.coins()); out.writeBoolean(wallet.roleChosen());
        out.writeInt(wallet.nextGearSerial());
        for (var counts : java.util.List.of(wallet.bought(), wallet.order(), wallet.active())) {
            out.writeInt(counts.size());
            for (var offer : ShopOffer.values()) {
                Integer count = counts.get(offer);
                if (count != null) { writeText(out, offer.id()); out.writeInt(count); }
            }
        }
        for (var pieces : java.util.List.of(wallet.gear(), wallet.activeGear())) {
            out.writeInt(pieces.size());
            for (var piece : pieces) {
                out.writeInt(piece.serial()); writeText(out, piece.slot().name());
                int roles = 0;
                for (var role : piece.roles()) roles |= 1 << role.ordinal();
                out.writeInt(roles); writeText(out, piece.item());
            }
        }
        out.writeInt(wallet.sortieDamage().size());
        for (var entry : wallet.sortieDamage().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey()).toList()) {
            writeUuid(out, entry.getKey());
            out.writeLong(entry.getValue().countedMilli()); out.writeLong(entry.getValue().weightedMilli());
            out.writeInt(entry.getValue().paid());
        }
        writeText(out, wallet.parked());
    }

    private static MonsterWallet readWallet(DataInputStream in, int schema) throws IOException {
        int coins = in.readInt();
        boolean chosen = in.readBoolean();
        int serial = in.readInt();
        var counts = new java.util.ArrayList<java.util.Map<ShopOffer, Integer>>();
        for (int map = 0; map < 3; map++) {
            int size = in.readInt();
            if (size < 0 || size > ShopOffer.values().length) throw new IOException("Invalid offer count");
            var values = new java.util.EnumMap<ShopOffer, Integer>(ShopOffer.class);
            for (int i = 0; i < size; i++) {
                if (values.put(ShopOffer.fromId(readText(in, 128)), in.readInt()) != null) throw new IOException("Duplicate offer");
            }
            counts.add(values);
        }
        var gear = new java.util.ArrayList<java.util.List<MonsterWallet.GearPiece>>();
        for (int list = 0; list < 2; list++) {
            int size = in.readInt();
            if (size < 0 || size > MonsterWallet.MAX_GEAR) throw new IOException("Invalid gear count");
            var pieces = new java.util.ArrayList<MonsterWallet.GearPiece>();
            for (int i = 0; i < size; i++) {
                int pieceSerial = in.readInt();
                var slot = MonsterWallet.GearSlot.valueOf(readText(in, 64));
                int mask = in.readInt();
                var roles = java.util.EnumSet.noneOf(MonsterRole.class);
                for (var role : MonsterRole.values()) if ((mask & 1 << role.ordinal()) != 0) roles.add(role);
                pieces.add(new MonsterWallet.GearPiece(pieceSerial, slot, roles, readText(in, MonsterWallet.MAX_GEAR_TEXT * 4)));
            }
            gear.add(pieces);
        }
        int damaged = in.readInt();
        if (damaged < 0 || damaged > MonsterWallet.MAX_DAMAGE_VICTIMS) throw new IOException("Invalid damage credit count");
        var damage = new LinkedHashMap<UUID, MonsterWallet.DamageCredit>();
        for (int i = 0; i < damaged; i++) {
            UUID victim = readUuid(in);
            if (damage.put(victim, new MonsterWallet.DamageCredit(in.readLong(), in.readLong(), in.readInt())) != null) {
                throw new IOException("Duplicate damage credit");
            }
        }
        // Schema 7 predates paused sorties: every stored return already ended its sortie.
        String parked = schema >= 8 ? readText(in, MonsterWallet.MAX_PARKED_TEXT * 4) : "";
        return new MonsterWallet(coins, chosen, counts.get(0), counts.get(1), counts.get(2),
                gear.get(0), gear.get(1), serial, damage, parked);
    }

    private static void writeUuid(DataOutputStream out, UUID id) throws IOException {
        out.writeLong(id.getMostSignificantBits()); out.writeLong(id.getLeastSignificantBits());
    }

    private static UUID readUuid(DataInputStream in) throws IOException { return new UUID(in.readLong(), in.readLong()); }

    private static void writeText(DataOutputStream out, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length); out.write(bytes);
    }

    private static String readText(DataInputStream in, int maxBytes) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > maxBytes || length > in.available()) throw new IOException("Invalid text length");
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(in.readNBytes(length))).toString();
    }
}
