package dev.hardrush.lobby;

import com.mojang.authlib.GameProfile;
import dev.hardrush.core.lobby.LobbyContext;
import dev.hardrush.core.lobby.plan.LobbyFunction;
import dev.hardrush.world.LobbyWorld;
import java.util.*;
import java.util.function.BiConsumer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.phys.Vec3;

/**
 * Per-connection, packet-only FULL mannequins. Never added to a world, player list or tracker.
 *
 * <p>Two layers: a {@link Target} is who stands in a slot and whether it can be hunted, and changing it rebuilds
 * the page; a {@link Look} is what the model shows (name line, glow, worn gear) and is updated in place, only
 * the parts that changed, so live gear and bounty never make the models flicker.
 */
public final class LobbyProjections {
    public enum State { AVAILABLE, AWAY, DEAD, EMPTY, RESTORING, CLOSED }
    /** Identity of a slot; clicks are judged on this layer only. */
    public record Target(GameProfile originalProfile, int life, State state) {
        public Target { Objects.requireNonNull(originalProfile); Objects.requireNonNull(state); if (life < 1) throw new IllegalArgumentException("life"); }
    }
    /** Presentation of a slot. {@code gear} holds display copies keyed by slot; a missing slot is shown empty. */
    public record Look(Component name, boolean glowing, Map<EquipmentSlot, ItemStack> gear) {
        public static final List<EquipmentSlot> SLOTS = List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
                EquipmentSlot.FEET, EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND);
        public Look { Objects.requireNonNull(name); gear = Map.copyOf(gear); }
        /** The bare look of a target nobody decorated: its name and, when it cannot be hunted, why. */
        public static Look plain(Target target) {
            var name = Component.literal(target.originalProfile.name());
            if (target.state != State.AVAILABLE) name.append(" · " + stateText(target.state));
            return new Look(name, false, Map.of());
        }
        ItemStack worn(EquipmentSlot slot) { return gear.getOrDefault(slot, ItemStack.EMPTY); }
    }
    public static String stateText(State state) {
        return switch (state) {
            case AWAY -> "离线"; case DEAD -> "已阵亡";
            case RESTORING -> "正在复生"; case AVAILABLE, CLOSED, EMPTY -> "暂不可追猎";
        };
    }
    public record Selection(UUID round, UUID target, int life, long generation, int entityId) {}
    public record View(long generation, int page, int pages, List<Selection> entities) {}
    private static final class Model {
        final Mannequin entity; final Target target; final Selection selection;
        Look shown;
        Model(Mannequin entity, Target target, Selection selection) { this.entity = entity; this.target = target; this.selection = selection; }
        Mannequin entity() { return entity; }
        Selection selection() { return selection; }
    }
    private static final class Page {
        final ServerPlayer owner; final ServerGamePacketListenerImpl connection; final UUID round;
        final long generation; final int page; final int pages; final List<Target> targets; final Map<Integer, Model> models;
        final BiConsumer<ServerPlayer, Selection> action, watch;
        Map<UUID, Look> looks;
        Page(ServerPlayer owner, ServerGamePacketListenerImpl connection, UUID round, long generation, int page, int pages,
             List<Target> targets, Map<Integer, Model> models, BiConsumer<ServerPlayer, Selection> action,
             BiConsumer<ServerPlayer, Selection> watch, Map<UUID, Look> looks) {
            this.owner = owner; this.connection = connection; this.round = round; this.generation = generation; this.page = page;
            this.pages = pages; this.targets = targets; this.models = models; this.action = action; this.watch = watch; this.looks = looks;
        }
    }
    private final LobbyInteractions router;
    private final Map<UUID, Page> pages = new HashMap<>();
    private long generation;
    // Uses vanilla's shared monotonic entity allocator. Fail closed before signed-int rollover;
    // NEVER setId/recycle a removed model, even for the same UUID or a replacement connection.
    private static int lastNativeId = -1;
    LobbyProjections(LobbyInteractions router) { this.router = router; }

    /** Supply stable UUID slots (including DEAD holes); ordering/state is the round service's responsibility. */
    public View show(ServerPlayer observer, UUID round, List<Target> targets, int page, BiConsumer<ServerPlayer, Selection> action) {
        return show(observer, round, targets, page, action, (actor, selected) -> { });
    }
    /** Right-click runs {@code action} (hunt); left-click runs {@code watch} (ride the target's camera). */
    public View show(ServerPlayer observer, UUID round, List<Target> targets, int page,
                     BiConsumer<ServerPlayer, Selection> action, BiConsumer<ServerPlayer, Selection> watch) {
        return show(observer, round, targets, Map.of(), page, action, watch);
    }
    /** Builds the page with each model already wearing its look; later looks go through {@link #decorate}. */
    public View show(ServerPlayer observer, UUID round, List<Target> targets, Map<UUID, Look> looks, int page,
                     BiConsumer<ServerPlayer, Selection> action, BiConsumer<ServerPlayer, Selection> watch) {
        router.requireThread(); looks = Map.copyOf(looks); Objects.requireNonNull(action); Objects.requireNonNull(watch); targets = List.copyOf(targets);
        if (router.validate(observer, round, LobbyContext.DISPLAY, observer.getEyePosition()) != LobbyInteractions.Result.ACCEPTED)
            throw new IllegalStateException("Display not available to this connection");
        if (targets.stream().map(t -> t.originalProfile.id()).distinct().count() != targets.size()) throw new IllegalArgumentException("Duplicate target UUID");
        // Slots are ordered by (y, z, x); a page holds one target per slot.
        var slots = LobbyWorld.open(observer.level().getServer()).plan().points(LobbyFunction.DISPLAY_SLOT);
        int capacity = slots.size();
        int count = Math.max(1, (targets.size() + capacity - 1) / capacity);
        if (page < 0 || page >= count) throw new IllegalArgumentException("Invalid page");
        var previous = pages.get(observer.getUUID());
        if (previous != null) remove(previous.owner); // invalidate even a replaced connection BEFORE any packet
        long next = generation = Math.incrementExact(generation);
        var models = new LinkedHashMap<Integer, Model>();
        for (int index = page * capacity; index < Math.min(targets.size(), (page + 1) * capacity); index++) {
            var target = targets.get(index);
            if (target.state == State.EMPTY) continue; // Preserve the stable hole without keeping a dead model forever.
            var model = new Mannequin(EntityTypes.MANNEQUIN, observer.level());
            int id = model.getId();
            if (id <= lastNativeId) throw new IllegalStateException("Native entity IDs exhausted; restart required");
            lastNativeId = id;
            var slot = slots.get(index % capacity);
            model.setPos(LobbyWorld.position(slot)); model.setYRot(slot.yaw()); model.setYHeadRot(slot.yaw());
            model.setNoGravity(true); model.setSilent(true);
            // Authenticated profile is never taken from OwnerSkin's rewritten player-info packets.
            model.setComponent(DataComponents.PROFILE, ResolvableProfile.createResolved(target.originalProfile));
            model.setCustomNameVisible(true);
            var look = lookOf(looks, target);
            model.setCustomName(look.name());
            model.setGlowingTag(look.glowing());
            for (var equipment : Look.SLOTS) model.setItemSlot(equipment, look.worn(equipment).copy());
            var selection = new Selection(round, target.originalProfile.id(), target.life, next, id);
            var built = new Model(model, target, selection);
            built.shown = look;
            models.put(id, built);
        }
        // Build the entire immutable mapping first. There is no target remap window.
        var display = new Page(observer, observer.connection, round, next, page, count, targets, Map.copyOf(models), action, watch, looks);
        for (var model : models.values()) {
            var entity = model.entity;
            observer.connection.send(new ClientboundAddEntityPacket(entity, 0, entity.blockPosition()));
            observer.connection.send(new ClientboundSetEntityDataPacket(entity.getId(), Objects.requireNonNull(entity.getEntityData().getNonDefaultValues())));
            entity.getEntityData().packDirty(); // Everything is sent; later looks send only what they change.
            var worn = new ArrayList<com.mojang.datafixers.util.Pair<EquipmentSlot, ItemStack>>();
            for (var equipment : Look.SLOTS) if (!model.shown.worn(equipment).isEmpty())
                worn.add(com.mojang.datafixers.util.Pair.of(equipment, model.shown.worn(equipment).copy()));
            if (!worn.isEmpty()) observer.connection.send(new ClientboundSetEquipmentPacket(entity.getId(), worn));
        }
        pages.put(observer.getUUID(), display);
        return view(observer).orElseThrow();
    }
    /** Called by a registered NEXT/PREVIOUS facility; never changes another connection's page. */
    public Optional<View> turn(ServerPlayer observer, int delta) {
        router.requireThread();
        if (delta != -1 && delta != 1) throw new IllegalArgumentException("Page delta must be +/-1");
        var current = pages.get(observer.getUUID());
        if (current == null || current.owner != observer || current.connection != observer.connection) return Optional.empty();
        int next = current.page + delta;
        if (next < 0 || next >= current.pages) return Optional.empty();
        return Optional.of(show(observer, current.round, current.targets, current.looks, next, current.action, current.watch));
    }

    /**
     * Updates the looks of this connection's current page in place: a changed name or glow sends the entity's
     * changed data, changed gear sends only the slots that differ. Unchanged models send nothing.
     */
    public void decorate(ServerPlayer observer, Map<UUID, Look> looks) {
        router.requireThread();
        var page = pages.get(observer.getUUID());
        if (page == null || page.owner != observer || page.connection != observer.connection) return;
        page.looks = Map.copyOf(looks);
        for (var model : page.models.values()) {
            var look = lookOf(page.looks, model.target);
            if (look.name().equals(model.shown.name()) && look.glowing() == model.shown.glowing() && sameGear(look, model.shown)) continue;
            var entity = model.entity;
            if (!look.name().equals(model.shown.name())) entity.setCustomName(look.name());
            if (look.glowing() != model.shown.glowing()) entity.setGlowingTag(look.glowing());
            var data = entity.getEntityData().packDirty();
            if (data != null) observer.connection.send(new ClientboundSetEntityDataPacket(entity.getId(), data));
            var changed = new ArrayList<com.mojang.datafixers.util.Pair<EquipmentSlot, ItemStack>>();
            for (var equipment : Look.SLOTS) {
                if (ItemStack.matches(look.worn(equipment), model.shown.worn(equipment))) continue;
                entity.setItemSlot(equipment, look.worn(equipment).copy());
                changed.add(com.mojang.datafixers.util.Pair.of(equipment, look.worn(equipment).copy()));
            }
            if (!changed.isEmpty()) observer.connection.send(new ClientboundSetEquipmentPacket(entity.getId(), changed));
            model.shown = look;
        }
    }

    private static Look lookOf(Map<UUID, Look> looks, Target target) {
        var look = target.state == State.EMPTY ? null : looks.get(target.originalProfile.id());
        return look != null ? look : Look.plain(target);
    }

    /** ItemStack has no value equality; display copies are compared by item, count and components. */
    private static boolean sameGear(Look a, Look b) {
        for (var equipment : Look.SLOTS) if (!ItemStack.matches(a.worn(equipment), b.worn(equipment))) return false;
        return true;
    }

    public Optional<View> view(ServerPlayer observer) {
        router.requireThread();
        var page = pages.get(observer.getUUID());
        if (page == null || page.owner != observer || page.connection != observer.connection) return Optional.empty();
        return Optional.of(new View(page.generation, page.page, page.pages,
                page.models.values().stream().map(Model::selection).sorted(Comparator.comparingInt(Selection::entityId)).toList()));
    }
    public Optional<Selection> aimed(ServerPlayer observer) {
        router.requireThread();
        var page = pages.get(observer.getUUID());
        if (page == null || page.owner != observer || page.connection != observer.connection) return Optional.empty();
        var eye = observer.getEyePosition(); var end = eye.add(observer.getViewVector(1).scale(5));
        return page.models.values().stream().filter(m -> m.target.state == State.AVAILABLE)
                .filter(m -> m.entity.getBoundingBox().inflate(.15).clip(eye, end).isPresent())
                .min(Comparator.comparingDouble(m -> m.entity.position().distanceToSqr(eye))).map(Model::selection);
    }
    LobbyInteractions.Result click(ServerPlayer observer, ServerboundInteractPacket packet) {
        var page = pages.get(observer.getUUID());
        if (page == null || page.owner != observer || page.connection != observer.connection) return LobbyInteractions.Result.UNREGISTERED;
        var model = page.models.get(packet.entityId());
        if (model == null) return LobbyInteractions.Result.UNREGISTERED;
        var result = router.validate(observer, page.round, LobbyContext.DISPLAY, model.entity.position().add(0, 1, 0));
        var hit = packet.location();
        if (result == LobbyInteractions.Result.ACCEPTED && (packet.hand() != InteractionHand.MAIN_HAND
                || packet.usingSecondaryAction() || hit != null && (!Double.isFinite(hit.lengthSqr()) || hit.lengthSqr() > 9)))
            result = LobbyInteractions.Result.INPUT;
        if (result == LobbyInteractions.Result.ACCEPTED && model.target.state != State.AVAILABLE) result = LobbyInteractions.Result.UNAVAILABLE;
        if (result == LobbyInteractions.Result.ACCEPTED) page.action.accept(observer, model.selection);
        return result;
    }
    LobbyInteractions.Result attack(ServerPlayer observer, int entityId) {
        var page = pages.get(observer.getUUID());
        if (page == null || page.owner != observer || page.connection != observer.connection) return LobbyInteractions.Result.UNREGISTERED;
        var model = page.models.get(entityId);
        if (model == null) return LobbyInteractions.Result.UNREGISTERED;
        var result = router.validate(observer, page.round, LobbyContext.DISPLAY, model.entity.position().add(0, 1, 0));
        if (result == LobbyInteractions.Result.ACCEPTED && model.target.state != State.AVAILABLE) result = LobbyInteractions.Result.UNAVAILABLE;
        if (result == LobbyInteractions.Result.ACCEPTED) page.watch.accept(observer, model.selection);
        return result;
    }
    public void remove(ServerPlayer observer) {
        router.requireThread(); var page = pages.get(observer.getUUID());
        if (page == null || page.owner != observer) return;
        pages.remove(observer.getUUID());
        if (page.connection == observer.connection && page.connection.isAcceptingMessages())
            page.connection.send(new ClientboundRemoveEntitiesPacket(page.models.keySet().stream().mapToInt(Integer::intValue).toArray()));
    }
    void tick() {
        for (var page : List.copyOf(pages.values())) {
            if (router.validate(page.owner, page.round, LobbyContext.DISPLAY, page.owner.getEyePosition()) != LobbyInteractions.Result.ACCEPTED)
                remove(page.owner);
        }
    }
    public void clear() { router.requireThread(); for (var page : List.copyOf(pages.values())) remove(page.owner); }
}
