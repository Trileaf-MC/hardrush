package dev.hardrush.revival;

import dev.hardrush.core.round.ParticipantRole;
import dev.hardrush.core.round.RoundSnapshot;
import dev.hardrush.notice.ItemText;
import dev.hardrush.notice.Notices;
import dev.hardrush.player.PlayerProfiles;
import dev.hardrush.world.RoundWorldBundle;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;

/**
 * 遗落之地 (docs/plan/遗落之地.md): a human revived at the altar lands with a native recovery compass whose needle the
 * vanilla client already points at the player's own {@code lastDeathLocation}; the revival writes the previous human
 * life's death back there before the body crosses into the round world, so the respawn packet carries it. Held, the
 * action bar adds the dimension, coordinates and distance. It vanishes within {@link #REACHED} blocks of the spot,
 * when thrown (the drop mixin swallows it) and with the life it belongs to.
 *
 * <p>The item carries its own target, so a granted compass survives a reconnect or restart; only the bridge from a
 * death to the next revival is in memory, and a restart in between costs that one compass.
 */
public final class FallenCompass {
    private static final String KEY = "hardrush_fallen_compass";
    /** Within this many blocks (3D) of the spot the compass has done its job. */
    static final int REACHED = 8;
    /** {@code claimed}: an altar revival has written it into the crossing; only then is the compass granted. */
    private record Mark(UUID round, int life, GlobalPos at, boolean claimed) {}
    /** Server → player → the latest human death not yet turned into a compass. */
    private static final Map<MinecraftServer, Map<UUID, Mark>> MARKS = new WeakHashMap<>();

    private FallenCompass() {}

    /**
     * A human's captured death in a round world: the spot the next revived life is pointed back to. A fall into the
     * void leaves nothing to go back for and a spot no one can reach, so it is not remembered.
     */
    public static void died(ServerPlayer player, UUID round, int life) {
        var marks = MARKS.computeIfAbsent(player.level().getServer(), s -> new HashMap<>());
        if (player.getBlockY() < player.level().getMinY()) { marks.remove(player.getUUID()); return; }
        var at = GlobalPos.of(player.level().dimension(), player.blockPosition());
        marks.put(player.getUUID(), new Mark(round, life, at, false));
    }

    /**
     * Server thread, just before an altar-revived body crosses into the round world: the vanilla death location the
     * client receives with that respawn packet, and the claim that lets the landed life receive the compass. Empty
     * without a remembered death, so a monster's death never shows there. A normal-mode native respawn never claims,
     * so it keeps vanilla's own death location and gets no compass.
     */
    public static Optional<GlobalPos> claim(MinecraftServer server, UUID player, UUID round, int life) {
        var mark = mark(server, player, round, life);
        mark.ifPresent(m -> MARKS.get(server).put(player, new Mark(m.round, m.life, m.at, true)));
        return mark.map(Mark::at);
    }

    private static Optional<Mark> mark(MinecraftServer server, UUID player, UUID round, int life) {
        var marks = MARKS.get(server);
        var mark = marks == null ? null : marks.get(player);
        return mark != null && mark.round.equals(round) && mark.life == life - 1 ? Optional.of(mark) : Optional.empty();
    }

    public static boolean is(ItemStack stack) {
        return !stack.isEmpty() && stack.is(Items.RECOVERY_COMPASS)
                && stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().contains(KEY);
    }

    /** Server thread, every tick: grant once after the revival lands, take back every copy no current life owns. */
    public static void tick(MinecraftServer server, RoundSnapshot round) {
        var marks = MARKS.get(server);
        if (marks != null) marks.values().removeIf(mark -> round == null || !mark.round.equals(round.roundId()));
        for (var player : server.getPlayerList().getPlayers()) {
            var participant = round == null ? null : round.participants().get(player.getUUID());
            boolean human = participant != null && participant.role() == ParticipantRole.HUMAN_ACTIVE;
            var inventory = player.getInventory();
            boolean reached = false, changed = false;
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                var stack = inventory.getItem(i);
                if (!is(stack)) continue;
                boolean owned = owns(player, participant, round, stack);
                boolean arrived = owned && near(player, at(data(stack)));
                if (owned && !arrived) continue;
                reached |= arrived;
                inventory.setItem(i, ItemStack.EMPTY);
                changed = true;
            }
            // Only the inventory may hold it (bundles refuse it); a cursor copy goes rather than linger past its life.
            if (is(player.containerMenu.getCarried()) && !owns(player, participant, round, player.containerMenu.getCarried())) {
                player.containerMenu.setCarried(ItemStack.EMPTY);
                changed = true;
            }
            if (human && PlayerProfiles.verifiedRoundBody(player)) {
                var mark = mark(server, player.getUUID(), round.roundId(), participant.lifeSequence());
                if (mark.isPresent() && mark.get().claimed) {
                    marks.remove(player.getUUID());
                    // Landing within reach of the spot: nothing to find the way to, and nothing to say.
                    if (!near(player, Optional.of(mark.get().at))) {
                        inventory.add(stack(player.getUUID(), round.roundId(), participant.lifeSequence(), mark.get().at));
                        changed = true;
                    }
                }
            }
            if (changed) player.inventoryMenu.broadcastChanges();
            if (reached) Notices.hint(player, "回到了遗落之地 · 追溯指针已消散");
        }
    }

    private static boolean owns(ServerPlayer player, dev.hardrush.core.round.Participant participant, RoundSnapshot round, ItemStack stack) {
        var data = data(stack);
        return participant != null && participant.role() == ParticipantRole.HUMAN_ACTIVE
                && data.getStringOr("owner", "").equals(player.getUUID().toString())
                && data.getStringOr("round", "").equals(round.roundId().toString())
                && data.getIntOr("life", -1) == participant.lifeSequence();
    }

    /** Death takes the compass with the life: it never becomes a floor stack for someone else's needle. */
    public static void stripForDeath(ServerPlayer player) {
        var inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) if (is(inventory.getItem(i))) inventory.setItem(i, ItemStack.EMPTY);
        if (is(player.containerMenu.getCarried())) player.containerMenu.setCarried(ItemStack.EMPTY);
    }

    /** Where it points and how far, on the action bar while held; also the first line of its description. */
    public static Optional<String> heldHint(ServerPlayer player, ItemStack stack) {
        if (!is(stack)) return Optional.empty();
        var at = at(data(stack));
        if (at.isEmpty()) return Optional.empty();
        var spot = at.get();
        String where = place(player.level().getServer(), spot);
        if (!spot.dimension().equals(player.level().dimension())) return Optional.of(where + " · 不在当前维度");
        double dx = spot.pos().getX() + .5 - player.getX(), dz = spot.pos().getZ() + .5 - player.getZ();
        String hint = where + " · 距离 " + Notices.key(Math.round(Math.sqrt(dx * dx + dz * dz))) + " 格";
        // The distance is horizontal like the needle; the spot only counts as reached within REACHED blocks in 3D,
        // so a deep cave or a cliff top says which way to go.
        int dy = spot.pos().getY() - player.getBlockY();
        if (Math.abs(dy) > REACHED) hint += " · " + (dy < 0 ? "下方 " : "上方 ") + Notices.key(Math.abs(dy)) + " 格";
        return Optional.of(hint);
    }

    static ItemStack stack(UUID owner, UUID round, int life, GlobalPos at) {
        var stack = ItemText.name(new ItemStack(Items.RECOVERY_COMPASS), "遗落之地");
        var data = new CompoundTag();
        data.putString("owner", owner.toString());
        data.putString("round", round.toString());
        data.putInt("life", life);
        data.putString("dimension", at.dimension().identifier().toString());
        data.putInt("x", at.pos().getX()); data.putInt("y", at.pos().getY()); data.putInt("z", at.pos().getZ());
        var tag = new CompoundTag();
        tag.put(KEY, data);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return ItemText.lore(stack, "指向你上次倒下的地方", "走到 " + Notices.key(REACHED) + " 格内、阵亡或丢出时消失");
    }

    /** Where a 遗落之地 compass points; empty for any other stack. */
    public static Optional<GlobalPos> target(ItemStack stack) { return is(stack) ? at(data(stack)) : Optional.empty(); }

    private static CompoundTag data(ItemStack stack) {
        return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getCompoundOrEmpty(KEY);
    }

    private static Optional<GlobalPos> at(CompoundTag data) {
        var id = Identifier.tryParse(data.getStringOr("dimension", ""));
        if (id == null || !data.contains("x")) return Optional.empty();
        return Optional.of(GlobalPos.of(ResourceKey.create(Registries.DIMENSION, id),
                new BlockPos(data.getIntOr("x", 0), data.getIntOr("y", 0), data.getIntOr("z", 0))));
    }

    private static boolean near(ServerPlayer player, Optional<GlobalPos> at) {
        return at.isPresent() && at.get().dimension().equals(player.level().dimension())
                && player.position().distanceToSqr(net.minecraft.world.phys.Vec3.atBottomCenterOf(at.get().pos())) <= REACHED * REACHED;
    }

    /** "下界 **x y z**": the round's dimensions by their vanilla names. */
    private static String place(MinecraftServer server, GlobalPos at) {
        var level = server.getLevel(at.dimension());
        var dimension = level == null ? null : RoundWorldBundle.semanticDimension(level);
        String name = Level.NETHER.equals(dimension) ? "下界" : Level.END.equals(dimension) ? "末地"
                : Level.OVERWORLD.equals(dimension) ? "主世界" : "未知维度";
        var p = at.pos();
        return name + " " + Notices.key(p.getX() + " " + p.getY() + " " + p.getZ());
    }
}
