package dev.hardrush.p1.lobby;

import com.mojang.datafixers.util.Pair;
import dev.hardrush.core.lobby.LobbyContext;
import dev.hardrush.core.lobby.LobbyLayout;
import dev.hardrush.p1.world.P1LobbyWorld;
import dev.hardrush.p2.lobby.P2LobbyRuntime;
import java.util.*;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.skeleton.Skeleton;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/** Native monster models, sent only as packets: no AI, damage, peaceful despawn or saved entities. */
public final class LobbyRoleModels {
    private record Model(LobbyLayout.Facility facility, Mob entity) {}
    private record Display(ServerPlayer owner, ServerGamePacketListenerImpl connection, UUID round,
                           Map<Integer, Model> models) {}
    private final LobbyInteractions router;
    private final Map<UUID, Display> displays = new HashMap<>();
    private static int lastNativeId = -1;

    LobbyRoleModels(LobbyInteractions router) { this.router = router; }

    /** IDs belong to this exact connection and round, like the human hunt projections. */
    public Map<LobbyLayout.Facility, Integer> view(ServerPlayer observer) {
        router.requireThread();
        var display = displays.get(observer.getUUID());
        if (display == null || display.owner != observer || display.connection != observer.connection) return Map.of();
        var ids = new EnumMap<LobbyLayout.Facility, Integer>(LobbyLayout.Facility.class);
        display.models.forEach((id, model) -> ids.put(model.facility, id));
        return Map.copyOf(ids);
    }

    void tick(MinecraftServer server) {
        router.requireThread();
        for (var display : List.copyOf(displays.values())) {
            if (!available(display.owner, display.round) || display.connection != display.owner.connection)
                remove(display.owner);
        }
        var round = router.round();
        for (var observer : server.getPlayerList().getPlayers()) {
            if (!available(observer, round) || displays.containsKey(observer.getUUID())) continue;
            var lobby = P1LobbyWorld.open(server);
            var models = new LinkedHashMap<Integer, Model>();
            for (var facility : LobbyLayout.MONSTER_FACILITIES) {
                // Templates bypass spawn admission: the peaceful lobby never spawns a live mob.
                Mob entity = switch (facility) {
                    case ZOMBIE -> new Zombie(EntityTypes.ZOMBIE, observer.level());
                    case SKELETON -> new Skeleton(EntityTypes.SKELETON, observer.level());
                    case CREEPER -> new Creeper(EntityTypes.CREEPER, observer.level());
                    default -> throw new IllegalStateException("Not a monster facility");
                };
                if (entity.getId() <= lastNativeId) throw new IllegalStateException("Native entity IDs exhausted; restart required");
                lastNativeId = entity.getId();
                var point = LobbyLayout.facility(facility);
                entity.setPos(lobby.layoutPosition(point)); entity.setYRot(point.yaw()); entity.setYHeadRot(point.yaw());
                entity.setNoAi(true); entity.setNoGravity(true); entity.setSilent(true);
                String name = switch (facility) {
                    case ZOMBIE -> "僵尸"; case SKELETON -> "骷髅"; case CREEPER -> "苦力怕";
                    default -> throw new IllegalStateException("Not a monster facility");
                };
                entity.setCustomName(Component.literal(name + " · 右键选择角色")); entity.setCustomNameVisible(true);
                if (facility == LobbyLayout.Facility.SKELETON) entity.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
                models.put(entity.getId(), new Model(facility, entity));
            }
            displays.put(observer.getUUID(), new Display(observer, observer.connection, round, Map.copyOf(models)));
            for (var model : models.values()) {
                var entity = model.entity;
                observer.connection.send(new ClientboundAddEntityPacket(entity.getId(), entity.getUUID(),
                        entity.getX(), entity.getY(), entity.getZ(), entity.getXRot(), entity.getYRot(),
                        entity.getType(), 0, Vec3.ZERO, entity.getYHeadRot()));
                observer.connection.send(new ClientboundSetEntityDataPacket(entity.getId(),
                        Objects.requireNonNull(entity.getEntityData().getNonDefaultValues())));
                if (!entity.getMainHandItem().isEmpty()) observer.connection.send(new ClientboundSetEquipmentPacket(entity.getId(),
                        List.of(Pair.of(EquipmentSlot.MAINHAND, entity.getMainHandItem().copy()))));
            }
        }
    }

    private boolean available(ServerPlayer observer, UUID round) {
        // Same availability gate as the former lecterns; the existing facility action owns role restrictions.
        return router.validate(observer, round, LobbyContext.RULES, observer.getEyePosition()) == LobbyInteractions.Result.ACCEPTED;
    }

    LobbyInteractions.Result click(ServerPlayer observer, ServerboundInteractPacket packet) {
        var display = displays.get(observer.getUUID());
        if (display == null || display.owner != observer || display.connection != observer.connection)
            return LobbyInteractions.Result.UNREGISTERED;
        var model = display.models.get(packet.entityId());
        if (model == null) return LobbyInteractions.Result.UNREGISTERED;
        var result = router.validate(observer, display.round, LobbyContext.RULES, model.entity.position().add(0, .5, 0));
        var hit = packet.location();
        if (result == LobbyInteractions.Result.ACCEPTED && (packet.hand() != InteractionHand.MAIN_HAND
                || hit != null && (!Double.isFinite(hit.lengthSqr()) || hit.lengthSqr() > 9)))
            result = LobbyInteractions.Result.INPUT;
        if (result == LobbyInteractions.Result.ACCEPTED) P2LobbyRuntime.facility(observer, model.facility);
        return result;
    }

    void remove(ServerPlayer observer) {
        router.requireThread();
        var display = displays.get(observer.getUUID());
        if (display == null || display.owner != observer) return;
        displays.remove(observer.getUUID());
        // A dimension change already cleared these IDs on the client.
        if (display.connection == observer.connection && display.connection.isAcceptingMessages() && P1LobbyWorld.isLobby(observer.level()))
            display.connection.send(new ClientboundRemoveEntitiesPacket(display.models.keySet().stream().mapToInt(Integer::intValue).toArray()));
    }

    void clear() { router.requireThread(); for (var display : List.copyOf(displays.values())) remove(display.owner); }
}
