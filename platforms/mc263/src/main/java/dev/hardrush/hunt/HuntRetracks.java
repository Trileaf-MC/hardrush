package dev.hardrush.hunt;

import dev.hardrush.core.round.ParticipantRole;
import dev.hardrush.core.round.RoundEngine;
import dev.hardrush.core.round.RoundSnapshot;
import dev.hardrush.monster.MonsterForm;
import dev.hardrush.monster.MonsterItems;
import dev.hardrush.monster.MonsterRoles;
import dev.hardrush.round.RoundRuntime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.game.ClientboundSetDefaultSpawnPositionPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelData;
import org.jspecify.annotations.Nullable;

/**
 * The tracking compass of a deployed monster. Its needle points at the bound quarry: a vanilla compass points at the
 * world spawn its client was last told about, so only this player's client is told the spawn stands where the quarry
 * is. A quarry in another dimension, or a lost one, is sent as nowhere at all, so the needle spins exactly as vanilla
 * does and the client never learns where the quarry went. The compass glints whenever a press would start a retrack
 * and rests after each landing. Arrivals near the quarry sound the species' native call.
 */
public final class HuntRetracks {
    public static final String PURPOSE = "retrack";
    public static final int ANNOUNCE_DISTANCE = 16;
    /** How often the needle follows a moving quarry; a dimension change is answered at once. */
    public static final int POINT_INTERVAL_TICKS = 20;
    /** A dimension no client stands in: the needle spins while the quarry is lost. */
    public static final ResourceKey<Level> NOWHERE =
            ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath("hardrush", "lost_quarry"));
    /** The body the needle was last pointed for, its dimension then, and when. */
    private record Pointer(ServerPlayer body, ResourceKey<Level> level, int sentTick) {}
    private static final Map<MinecraftServer, Map<UUID, Pointer>> POINTERS = new IdentityHashMap<>();
    private HuntRetracks() {}

    /**
     * Server tick: the glint and the needle. {@code round} is null outside the challenge and rescue phases;
     * then, as for anyone no longer deployed, the needle goes back to the real world spawn.
     * @param ready whether a press would start a retrack now, apart from the compass's rest: the same rule the press obeys
     */
    public static void tick(MinecraftServer server, @Nullable RoundSnapshot round, Predicate<ServerPlayer> ready) {
        var pointers = POINTERS.computeIfAbsent(server, ignored -> new HashMap<>());
        int now = server.getTickCount();
        var online = new HashSet<UUID>();
        for (var player : server.getPlayerList().getPlayers()) {
            online.add(player.getUUID());
            var old = pointers.get(player.getUUID());
            if (round == null || !deployed(player, round)) {
                if (old == null) continue;
                pointers.remove(player.getUUID());
                // A fresh body was already told the real spawn by vanilla's own login or respawn.
                if (old.body == player) restore(player);
                continue;
            }
            light(player, ready.test(player));
            var level = player.level().dimension();
            if (old == null || old.body != player || old.level != level || now - old.sentTick >= POINT_INTERVAL_TICKS) {
                send(player, pointAt(player, quarry(player, round).orElse(null)));
                pointers.put(player.getUUID(), new Pointer(player, level, now));
            }
        }
        pointers.keySet().retainAll(online);
    }
    /** Vanilla just told this client the real spawn: a needle already pointing at a quarry points there again at once. */
    public static void levelInfoSent(ServerPlayer player) {
        var pointer = POINTERS.getOrDefault(player.level().getServer(), Map.of()).get(player.getUUID());
        if (pointer == null || pointer.body != player) return;
        send(player, needle(player));
        POINTERS.get(player.level().getServer()).put(player.getUUID(), new Pointer(player, player.level().dimension(), player.level().getServer().getTickCount()));
    }
    private static boolean deployed(ServerPlayer player, RoundSnapshot round) {
        var p = round.participants().get(player.getUUID());
        return p != null && p.role() == ParticipantRole.MONSTER_DEPLOYED && MonsterRoles.isMonster(player);
    }

    /**
     * Where the hunter's compass needle points: the quarry's block in the hunter's own dimension; otherwise nowhere
     * any client stands, so it spins and the packet carries no position of the quarry.
     */
    public static GlobalPos needle(ServerPlayer hunter) {
        return pointAt(hunter, quarry(hunter).orElse(null));
    }
    private static GlobalPos pointAt(ServerPlayer hunter, @Nullable ServerPlayer target) {
        return target == null || target.level() != hunter.level() ? GlobalPos.of(NOWHERE, BlockPos.ZERO)
                : GlobalPos.of(target.level().dimension(), target.blockPosition());
    }
    private static void send(ServerPlayer player, GlobalPos at) {
        if (player.connection != null) player.connection.send(new ClientboundSetDefaultSpawnPositionPacket(new LevelData.RespawnData(at, 0, 0)));
    }
    /** The same spawn vanilla sends with the level's other info. */
    private static void restore(ServerPlayer player) {
        if (player.connection != null) player.connection.send(new ClientboundSetDefaultSpawnPositionPacket(player.level().getRespawnData()));
    }

    public static Optional<ServerPlayer> quarry(ServerPlayer hunter) {
        return RoundRuntime.snapshot(hunter.level().getServer()).flatMap(round -> quarry(hunter, round));
    }
    public static OptionalInt quarryLife(ServerPlayer hunter) {
        return RoundRuntime.snapshot(hunter.level().getServer()).flatMap(round -> HuntBossBars.hunted(hunter, round))
                .map(h -> OptionalInt.of(h.targetLife())).orElse(OptionalInt.empty());
    }
    private static Optional<ServerPlayer> quarry(ServerPlayer hunter, RoundSnapshot round) {
        return HuntBossBars.hunted(hunter, round).flatMap(hunted -> {
            var p = round.participants().get(hunted.target());
            var body = hunter.level().getServer().getPlayerList().getPlayer(hunted.target());
            return p != null && p.online() && p.role() == ParticipantRole.HUMAN_ACTIVE && p.lifeSequence() == hunted.targetLife()
                    && body != null && body.isAlive() ? Optional.of(body) : Optional.empty();
        });
    }

    /** The compass's rest after a landing, from the round's rules. */
    public static int cooldownTicks(MinecraftServer server) {
        long millis = RoundRuntime.snapshot(server).map(round -> RoundEngine.retrackCooldownMillis(round.rules()))
                .orElse(RoundEngine.RETRACK_COOLDOWN_MILLIS);
        return (int) Math.max(1, Math.min(Integer.MAX_VALUE, millis / 50));
    }

    /** A personal hunt landed. */
    public static void arrived(ServerPlayer hunter, ServerPlayer target) { announce(hunter, target); }
    /** A retrack landed: the compass rests, wherever it is held, even on the inventory cursor. */
    public static void retracked(ServerPlayer hunter, ServerPlayer target) {
        announce(hunter, target);
        hunter.getCooldowns().addCooldown(cooldownGroup(hunter), cooldownTicks(hunter.level().getServer()));
        light(hunter, false);
    }
    /** The compass's native cooldown group: it rests as an item kind, not as one stack in one slot. */
    public static Identifier cooldownGroup(ServerPlayer hunter) {
        return hunter.getCooldowns().getCooldownGroup(new ItemStack(Items.COMPASS));
    }
    public static boolean resting(ServerPlayer hunter) {
        return hunter.getCooldowns().isOnCooldown(new ItemStack(Items.COMPASS));
    }

    /** Vanilla mobs groan or rattle now and then; a creeper stays the silent one. */
    private static void announce(ServerPlayer hunter, ServerPlayer target) {
        if (target.level() != hunter.level() || target.distanceToSqr(hunter) > ANNOUNCE_DISTANCE * ANNOUNCE_DISTANCE) return;
        var form = MonsterRoles.currentForm(hunter);
        if (form == null || form == MonsterForm.CREEPER) return;
        var sound = switch (form) {
            case ZOMBIE -> SoundEvents.ZOMBIE_AMBIENT; case DROWNED -> SoundEvents.DROWNED_AMBIENT;
            case SKELETON -> SoundEvents.SKELETON_AMBIENT; case STRAY -> SoundEvents.STRAY_AMBIENT;
            case CREEPER -> throw new IllegalStateException();
        };
        hunter.level().playSound(null, hunter.getX(), hunter.getY(), hunter.getZ(), sound, SoundSource.HOSTILE, 1, 1);
    }
    /** Glints while a press would start a retrack: {@code ready}, and no rest or channel overlay on the compass. */
    private static void light(ServerPlayer hunter, boolean ready) {
        boolean lit = ready && !resting(hunter);
        for (var stack : compasses(hunter)) {
            if (!Boolean.valueOf(lit).equals(stack.get(DataComponents.ENCHANTMENT_GLINT_OVERRIDE)))
                stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, lit);
        }
    }
    private static List<ItemStack> compasses(ServerPlayer hunter) {
        var inventory = hunter.getInventory();
        var found = new ArrayList<ItemStack>();
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++)
            if (MonsterItems.matches(hunter, inventory.getItem(slot), PURPOSE)) found.add(inventory.getItem(slot));
        var offhand = inventory.getItem(Inventory.SLOT_OFFHAND);
        if (MonsterItems.matches(hunter, offhand, PURPOSE)) found.add(offhand);
        return found;
    }

    public static void stop(MinecraftServer server) { POINTERS.remove(server); }
}
