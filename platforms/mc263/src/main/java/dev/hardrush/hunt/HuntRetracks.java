package dev.hardrush.hunt;

import dev.hardrush.core.round.ParticipantRole;
import dev.hardrush.core.round.RoundSnapshot;
import dev.hardrush.monster.MonsterForm;
import dev.hardrush.monster.MonsterItems;
import dev.hardrush.monster.MonsterRoles;
import dev.hardrush.round.RoundRuntime;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * The tracking compass of a deployed monster: lit once the quarry has slipped away (far for a while, or into
 * another dimension), cooling down after each use. Arrivals near the quarry sound the species' native ambient call.
 */
public final class HuntRetracks {
    public static final String PURPOSE = "retrack";
    public static final int FAR_DISTANCE = 40;
    public static final int FAR_SECONDS = 10;
    public static final int COOLDOWN_TICKS = 15 * 20;
    public static final int ANNOUNCE_DISTANCE = 16;
    private static final Map<MinecraftServer, Map<UUID, Integer>> FAR_SINCE = new IdentityHashMap<>();
    private HuntRetracks() {}

    /** Server tick while a round is running: the far timer and the compass glint. */
    public static void tick(MinecraftServer server, RoundSnapshot round) {
        var far = FAR_SINCE.computeIfAbsent(server, ignored -> new HashMap<>());
        far.keySet().removeIf(id -> Optional.ofNullable(round.participants().get(id))
                .filter(p -> p.role() == ParticipantRole.MONSTER_DEPLOYED).isEmpty());
        for (var player : server.getPlayerList().getPlayers()) {
            if (!MonsterRoles.isMonster(player)) continue;
            var target = quarry(player, round).orElse(null);
            if (target == null || target.level() != player.level()
                    || target.position().distanceToSqr(player.position()) <= FAR_DISTANCE * FAR_DISTANCE) far.remove(player.getUUID());
            else far.putIfAbsent(player.getUUID(), server.getTickCount());
            light(player, available(player, round));
        }
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

    /** The quarry slipped away: another dimension at once, or beyond 40 blocks for 10 seconds. */
    public static boolean available(ServerPlayer hunter) {
        return RoundRuntime.snapshot(hunter.level().getServer()).map(round -> available(hunter, round)).orElse(false);
    }
    private static boolean available(ServerPlayer hunter, RoundSnapshot round) {
        var target = quarry(hunter, round).orElse(null);
        if (target == null) return false;
        if (target.level() != hunter.level()) return true;
        var far = FAR_SINCE.getOrDefault(hunter.level().getServer(), Map.of()).get(hunter.getUUID());
        return far != null && hunter.level().getServer().getTickCount() - far >= FAR_SECONDS * 20;
    }

    /** A personal hunt landed. */
    public static void arrived(ServerPlayer hunter, ServerPlayer target) {
        reset(hunter); announce(hunter, target);
    }
    /** A retrack landed: the compass cools down for 15 seconds. */
    public static void retracked(ServerPlayer hunter, ServerPlayer target) {
        reset(hunter); announce(hunter, target);
        for (var stack : compasses(hunter)) { hunter.getCooldowns().addCooldown(stack, COOLDOWN_TICKS); break; }
        light(hunter, false);
    }

    private static void reset(ServerPlayer hunter) {
        var far = FAR_SINCE.get(hunter.level().getServer());
        if (far != null) far.remove(hunter.getUUID());
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
    private static void light(ServerPlayer hunter, boolean available) {
        for (var stack : compasses(hunter)) {
            boolean lit = available && !hunter.getCooldowns().isOnCooldown(stack);
            if (!Boolean.valueOf(lit).equals(stack.get(DataComponents.ENCHANTMENT_GLINT_OVERRIDE)))
                stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, lit);
        }
    }
    private static java.util.List<ItemStack> compasses(ServerPlayer hunter) {
        var inventory = hunter.getInventory();
        var found = new java.util.ArrayList<ItemStack>();
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++)
            if (MonsterItems.matches(hunter, inventory.getItem(slot), PURPOSE)) found.add(inventory.getItem(slot));
        var offhand = inventory.getItem(Inventory.SLOT_OFFHAND);
        if (MonsterItems.matches(hunter, offhand, PURPOSE)) found.add(offhand);
        return found;
    }

    public static void stop(MinecraftServer server) { FAR_SINCE.remove(server); }
}
