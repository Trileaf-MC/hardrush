package dev.hardrush.lobby;

import com.mojang.datafixers.util.Pair;
import dev.hardrush.core.lobby.LobbyContext;
import dev.hardrush.core.lobby.plan.LobbyFunction;
import dev.hardrush.world.LobbyWorld;
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
    private record Model(LobbyFunction function, Mob entity) {}
    private static final class Display {
        final ServerPlayer owner; final ServerGamePacketListenerImpl connection; final UUID round;
        final Map<Integer, Model> models;
        /** Whether the name tags currently say the courtyard is open. */
        boolean open;
        Display(ServerPlayer owner, ServerGamePacketListenerImpl connection, UUID round, Map<Integer, Model> models, boolean open) {
            this.owner = owner; this.connection = connection; this.round = round; this.models = models; this.open = open;
        }
    }
    private static final List<LobbyFunction> FUNCTIONS = List.of(LobbyFunction.ROLE_ZOMBIE, LobbyFunction.ROLE_SKELETON,
            LobbyFunction.ROLE_CREEPER, LobbyFunction.MERCHANT);
    private final LobbyInteractions router;
    private final Map<UUID, Display> displays = new HashMap<>();
    private static int lastNativeId = -1;

    LobbyRoleModels(LobbyInteractions router) { this.router = router; }

    /** IDs belong to this exact connection and round, like the human hunt projections. */
    public Map<LobbyFunction, List<Integer>> view(ServerPlayer observer) {
        router.requireThread();
        var display = displays.get(observer.getUUID());
        if (display == null || display.owner != observer || display.connection != observer.connection) return Map.of();
        var ids = new EnumMap<LobbyFunction, List<Integer>>(LobbyFunction.class);
        display.models.forEach((id, model) -> ids.computeIfAbsent(model.function, ignored -> new ArrayList<>()).add(id));
        var copy = new EnumMap<LobbyFunction, List<Integer>>(LobbyFunction.class);
        ids.forEach((function, list) -> copy.put(function, List.copyOf(list)));
        return Collections.unmodifiableMap(copy);
    }

    void tick(MinecraftServer server) {
        router.requireThread();
        for (var display : List.copyOf(displays.values())) {
            if (!available(display.owner, display.round) || display.connection != display.owner.connection)
                remove(display.owner);
        }
        var round = router.round();
        boolean open = LobbyRuntime.courtyardOpen(server);
        for (var display : displays.values()) {
            if (display.open == open) continue;
            display.open = open;
            for (var model : display.models.values()) {
                name(model, open);
                display.connection.send(new ClientboundSetEntityDataPacket(model.entity.getId(),
                        Objects.requireNonNull(model.entity.getEntityData().getNonDefaultValues())));
            }
        }
        for (var observer : server.getPlayerList().getPlayers()) {
            if (!available(observer, round) || displays.containsKey(observer.getUUID())) continue;
            var plan = LobbyWorld.open(server).plan();
            var models = new LinkedHashMap<Integer, Model>();
            for (var function : FUNCTIONS) for (var placed : plan.models(function)) {
                // Templates bypass spawn admission: the peaceful lobby never spawns a live mob.
                Mob entity = switch (function) {
                    case ROLE_ZOMBIE -> new Zombie(EntityTypes.ZOMBIE, observer.level());
                    case ROLE_SKELETON -> new Skeleton(EntityTypes.SKELETON, observer.level());
                    case ROLE_CREEPER -> new Creeper(EntityTypes.CREEPER, observer.level());
                    case MERCHANT -> new net.minecraft.world.entity.monster.Witch(EntityTypes.WITCH, observer.level());
                    default -> throw new IllegalStateException("Not a model function");
                };
                if (entity.getId() <= lastNativeId) throw new IllegalStateException("Native entity IDs exhausted; restart required");
                lastNativeId = entity.getId();
                var point = placed.point();
                entity.setPos(LobbyWorld.position(point)); entity.setYRot(point.yaw()); entity.setYHeadRot(point.yaw());
                entity.setNoAi(true); entity.setNoGravity(true); entity.setSilent(true);
                entity.setCustomNameVisible(true);
                if (function == LobbyFunction.ROLE_SKELETON) entity.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
                var model = new Model(function, entity);
                name(model, open);
                models.put(entity.getId(), model);
            }
            displays.put(observer.getUUID(), new Display(observer, observer.connection, round, Map.copyOf(models), open));
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

    /** Outside a personal hunt the name tag says when the model works, instead of a sign beside it. */
    private static void name(Model model, boolean open) {
        String who = switch (model.function) {
            case ROLE_ZOMBIE -> "僵尸"; case ROLE_SKELETON -> "骷髅"; case ROLE_CREEPER -> "苦力怕";
            case MERCHANT -> "女巫商人";
            default -> throw new IllegalStateException("Not a model function");
        };
        String what = !open ? "极限挑战时开放" : model.function == LobbyFunction.MERCHANT ? "右键交易" : "右键选择角色";
        model.entity.setCustomName(Component.literal(who + " · " + what));
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
        if (result == LobbyInteractions.Result.ACCEPTED) LobbyRuntime.activate(observer, model.function, null);
        return result;
    }

    void remove(ServerPlayer observer) {
        router.requireThread();
        var display = displays.get(observer.getUUID());
        if (display == null || display.owner != observer) return;
        displays.remove(observer.getUUID());
        // A dimension change already cleared these IDs on the client.
        if (display.connection == observer.connection && display.connection.isAcceptingMessages() && LobbyWorld.isLobby(observer.level()))
            display.connection.send(new ClientboundRemoveEntitiesPacket(display.models.keySet().stream().mapToInt(Integer::intValue).toArray()));
    }

    void clear() { router.requireThread(); for (var display : List.copyOf(displays.values())) remove(display.owner); }
}
