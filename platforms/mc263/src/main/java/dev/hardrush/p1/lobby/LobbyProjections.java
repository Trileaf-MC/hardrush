package dev.hardrush.p1.lobby;

import com.mojang.authlib.GameProfile;
import dev.hardrush.core.lobby.LobbyContext;
import dev.hardrush.core.lobby.LobbyLayout;
import dev.hardrush.p1.world.P1LobbyWorld;
import java.util.*;
import java.util.function.BiConsumer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.phys.Vec3;

/** Per-connection, packet-only FULL mannequins. Never added to a world, player list or tracker. */
public final class LobbyProjections {
    public enum State { AVAILABLE, AWAY, DEAD, CLOSED }
    public record Target(GameProfile originalProfile, int life, State state) {
        public Target { Objects.requireNonNull(originalProfile); Objects.requireNonNull(state); if (life < 1) throw new IllegalArgumentException("life"); }
    }
    public record Selection(UUID round, UUID target, int life, long generation, int entityId) {}
    public record View(long generation, int page, int pages, List<Selection> entities) {}
    private record Model(Mannequin entity, Target target, Selection selection) {}
    private record Page(ServerPlayer owner, ServerGamePacketListenerImpl connection, UUID round,
                        long generation, int page, int pages, List<Target> targets, Map<Integer, Model> models,
                        BiConsumer<ServerPlayer, Selection> action) {}
    private final LobbyInteractions router;
    private final Map<UUID, Page> pages = new HashMap<>();
    private long generation;
    // Uses vanilla's shared monotonic entity allocator. Fail closed before signed-int rollover;
    // NEVER setId/recycle a removed model, even for the same UUID or a replacement connection.
    private static int lastNativeId = -1;
    LobbyProjections(LobbyInteractions router) { this.router = router; }

    /** Supply stable UUID slots (including DEAD holes); ordering/state is the round service's responsibility. */
    public View show(ServerPlayer observer, UUID round, List<Target> targets, int page,
                     BiConsumer<ServerPlayer, Selection> action) {
        router.requireThread(); Objects.requireNonNull(action); targets = List.copyOf(targets);
        var origin = P1LobbyWorld.open(observer.level().getServer()).holdingSpawn();
        if (router.validate(observer, round, LobbyContext.DISPLAY, observer.getEyePosition()) != LobbyInteractions.Result.ACCEPTED)
            throw new IllegalStateException("Display not available to this connection");
        if (targets.stream().map(t -> t.originalProfile.id()).distinct().count() != targets.size()) throw new IllegalArgumentException("Duplicate target UUID");
        int count = Math.max(1, (targets.size() + LobbyLayout.DISPLAY_CAPACITY - 1) / LobbyLayout.DISPLAY_CAPACITY);
        if (page < 0 || page >= count) throw new IllegalArgumentException("Invalid page");
        var previous = pages.get(observer.getUUID());
        if (previous != null) remove(previous.owner); // invalidate even a replaced connection BEFORE any packet
        long next = generation = Math.incrementExact(generation);
        var models = new LinkedHashMap<Integer, Model>();
        for (int index = page * LobbyLayout.DISPLAY_CAPACITY; index < Math.min(targets.size(), (page + 1) * LobbyLayout.DISPLAY_CAPACITY); index++) {
            var target = targets.get(index);
            var model = new Mannequin(EntityTypes.MANNEQUIN, observer.level());
            int id = model.getId();
            if (id <= lastNativeId) throw new IllegalStateException("Native entity IDs exhausted; restart required");
            lastNativeId = id;
            var slot = LobbyLayout.displaySlots().get(index % LobbyLayout.DISPLAY_CAPACITY);
            model.setPos(origin.add(slot.x(), 0, slot.z())); model.setYRot(slot.yaw()); model.setYHeadRot(slot.yaw());
            model.setNoGravity(true); model.setSilent(true);
            // Authenticated profile is never taken from OwnerSkin's rewritten player-info packets.
            model.setComponent(DataComponents.PROFILE, ResolvableProfile.createResolved(target.originalProfile));
            model.setCustomName(Component.literal(target.originalProfile.name() + " · " + switch (target.state) {
                case AVAILABLE -> "右键查看"; case AWAY -> "暂离"; case DEAD -> "已死亡"; case CLOSED -> "未开放";
            }));
            model.setCustomNameVisible(true);
            var selection = new Selection(round, target.originalProfile.id(), target.life, next, id);
            models.put(id, new Model(model, target, selection));
        }
        // Build the entire immutable mapping first. There is no target remap window.
        var display = new Page(observer, observer.connection, round, next, page, count, targets, Map.copyOf(models), action);
        for (var model : models.values()) {
            var entity = model.entity;
            observer.connection.send(new ClientboundAddEntityPacket(entity, 0, entity.blockPosition()));
            observer.connection.send(new ClientboundSetEntityDataPacket(entity.getId(), Objects.requireNonNull(entity.getEntityData().getNonDefaultValues())));
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
        return Optional.of(show(observer, current.round, current.targets, next, current.action));
    }

    public Optional<View> view(ServerPlayer observer) {
        router.requireThread();
        var page = pages.get(observer.getUUID());
        if (page == null || page.owner != observer || page.connection != observer.connection) return Optional.empty();
        return Optional.of(new View(page.generation, page.page, page.pages,
                page.models.values().stream().map(Model::selection).sorted(Comparator.comparingInt(Selection::entityId)).toList()));
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
