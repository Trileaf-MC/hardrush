package dev.hardrush.p0.disguise;

import com.mojang.datafixers.util.Pair;
import dev.hardrush.p0.monster.MonsterRoles;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

/**
 * Observer-only monster appearance. The server player is the sole movement and
 * combat entity; the unregistered mob is only a metadata template. The owner
 * receives native player packets and a temporary Steve skin, never a self mob,
 * invisibility flag, private team, or camera replacement.
 *
 * <p>Selection survives vanilla respawn, dimension transfer and reconnect within
 * this server session. The future race controller owns durable identity and calls
 * apply/clear at its role boundaries; appearance never decides a gameplay role.</p>
 */
public final class DisguiseProbe {
    private static final Map<UUID, Selection> SELECTED = new ConcurrentHashMap<>();
    private static final Map<Integer, State> BY_ENTITY = new ConcurrentHashMap<>();
    private static final ThreadLocal<Boolean> RAW_SEND = ThreadLocal.withInitial(() -> false);

    private DisguiseProbe() {}

    public static String apply(ServerPlayer player, String requestedType) {
        requireMainThread(player);
        String kind = requestedType.toLowerCase(Locale.ROOT);
        EntityType<? extends Mob> type = switch (kind) {
            case "zombie" -> EntityTypes.ZOMBIE;
            case "drowned" -> EntityTypes.DROWNED;
            case "stray" -> EntityTypes.STRAY;
            case "skeleton" -> EntityTypes.SKELETON;
            case "creeper" -> EntityTypes.CREEPER;
            default -> null;
        };
        if (type == null) return "拒绝：只接受 zombie、skeleton、creeper。";
        if (!player.isAlive() || player.isSpectator()) return "拒绝：请在存活、非旁观时设置怪物外观。";
        Selection previous = SELECTED.get(player.getUUID());
        if (previous != null && previous.kind.equals(kind) && previous.state != null
                && previous.state.owner == player && previous.state.level == player.level()) {
            return status(player);
        }
        if (previous != null) unbind(previous);
        OwnerSkin.cancelRestore(player);
        Selection selection = new Selection(kind, type);
        SELECTED.put(player.getUUID(), selection);
        bind(player, selection);
        selection.skinRefreshPending = previous == null || previous.skinRefreshPending;
        refreshSkinIfSafe(player, selection);
        return "已设置旁人怪物外观，本人使用原版玩家模型／Steve 皮肤。" + status(player);
    }

    public static void clear(ServerPlayer player) {
        requireMainThread(player);
        Selection selection = SELECTED.remove(player.getUUID());
        if (selection == null) return;
        unbind(selection);
        for (ServerPlayer observer : PlayerLookup.tracking(player)) refreshObserver(observer, player, null);
        OwnerSkin.restore(player);
    }

    public static void onDisconnect(ServerPlayer player) {
        requireMainThread(player);
        OwnerSkin.onDisconnect(player);
        Selection selection = SELECTED.get(player.getUUID());
        if (selection != null) {
            unbind(selection);
            selection.skinRefreshPending = true;
        }
    }

    public static void onJoin(ServerPlayer player) {
        requireMainThread(player);
        Selection selection = SELECTED.get(player.getUUID());
        if (selection != null) {
            unbind(selection);
            bind(player, selection);
            selection.skinRefreshPending = true;
        }
        OwnerSkin.onJoin(player);
    }

    public static void stop(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("Disguise operations require the server thread");
        SELECTED.clear();
        BY_ENTITY.clear();
        OwnerSkin.stop();
    }

    public static void tick(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("Disguise operations require the server thread");
        OwnerSkin.tick(server);
        for (var entry : SELECTED.entrySet()) {
            Selection selection = entry.getValue();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) continue;
            State state = selection.state;
            if (state == null || state.owner != player || state.level != player.level()) {
                unbind(selection);
                bind(player, selection);
                selection.skinRefreshPending = true;
                state = selection.state;
            }
            if (player.isSpectator()) {
                clear(player);
                continue;
            }
            // Death retains selection; vanilla respawn rebinds the new player.
            if (!player.isAlive()) continue;
            refreshSkinIfSafe(player, selection);
            List<SynchedEntityData.DataValue<?>> before = state.metadata;
            state.updateModel();
            if (!before.equals(state.metadata)) {
                for (ServerPlayer observer : PlayerLookup.tracking(player)) {
                    sendRaw(observer, new ClientboundSetEntityDataPacket(state.realId, state.metadata));
                }
            }
        }
    }

    public static String status(ServerPlayer player) {
        Selection selection = SELECTED.get(player.getUUID());
        if (selection == null) return "disguise=off; realId=" + player.getId();
        return "disguise=" + selection.kind + "; realId=" + player.getId()
            + "; selfView=player; selfSpawned=false; selfSkin=steve"
            + "; skinRefresh=" + (selection.skinRefreshPending ? "pending-safe-point" : "sent")
            + "; physics=player; equipment=preserved; visualAcceptance=pending";
    }

    /** Does not mutate shared packets or the owner's authoritative entity data. */
    public static Packet<?> rewrite(ServerPlayer observer, Packet<?> packet) {
        if (RAW_SEND.get()) return packet;
        if (packet instanceof ClientboundBundlePacket bundle) {
            List<Packet<? super ClientGamePacketListener>> rewritten = new ArrayList<>();
            boolean changed = false;
            for (Packet<? super ClientGamePacketListener> child : bundle.subPackets()) {
                Packet<?> replacement = rewrite(observer, child);
                changed |= replacement != child;
                rewritten.add(asGamePacket(replacement));
            }
            return changed ? new ClientboundBundlePacket(rewritten) : packet;
        }
        if (packet instanceof ClientboundAddEntityPacket spawn) {
            State state = externalState(observer, spawn.getId());
            if (state != null) return new ClientboundAddEntityPacket(spawn.getId(), spawn.getUUID(),
                spawn.getX(), spawn.getY(), spawn.getZ(), spawn.getXRot(), spawn.getYRot(),
                state.type, 0, spawn.getMovement(), spawn.getYHeadRot());
        } else if (packet instanceof ClientboundSetEntityDataPacket data) {
            State state = externalState(observer, data.id());
            if (state != null) {
                if (state.owner.level().getServer().isSameThread()) state.updateModel();
                return new ClientboundSetEntityDataPacket(data.id(), state.metadata);
            }
        } else if (packet instanceof ClientboundUpdateAttributesPacket attributes) {
            State state = externalState(observer, attributes.getEntityId());
            if (state != null) return state.attributes();
        } else if (packet instanceof ClientboundSetEquipmentPacket equipment) {
            State state=externalState(observer,equipment.getEntity());
            if (state!=null) {
                if (state.owner.level().getServer().isSameThread()) state.updateModel();
                return new ClientboundSetEquipmentPacket(state.realId,state.equipment);
            }
        }
        return OwnerSkin.rewrite(observer, packet, SELECTED.containsKey(observer.getUUID()));
    }

    public static boolean captureCosmeticBatch(ServerPlayer player, Packet<?> packet) {
        return OwnerSkin.capture(player, packet);
    }

    public static void refreshEquipment(ServerPlayer player) {
        Selection selection=SELECTED.get(player.getUUID());
        if (selection==null || selection.state==null) return;
        State state=selection.state; state.updateModel();
        for (ServerPlayer observer:PlayerLookup.tracking(player))
            sendRaw(observer,new ClientboundSetEquipmentPacket(player.getId(),state.equipment));
    }

    public static void setCreeperSwelling(ServerPlayer player, boolean swelling) {
        Selection selection = SELECTED.get(player.getUUID());
        if (selection != null && selection.state != null
                && selection.state.model instanceof net.minecraft.world.entity.monster.Creeper creeper) {
            creeper.setSwellDir(swelling ? 1 : -1);
            selection.state.updateModel();
            for (ServerPlayer observer : PlayerLookup.tracking(player)) {
                sendRaw(observer, new ClientboundSetEntityDataPacket(player.getId(), selection.state.metadata));
            }
        }
    }

    public static void setCreeperState(ServerPlayer player, int direction, boolean charged) {
        Selection selection = SELECTED.get(player.getUUID());
        if (selection == null || selection.state == null || !(selection.state.model instanceof net.minecraft.world.entity.monster.Creeper creeper)) return;
        creeper.setSwellDir(direction);
        creeper.getEntityData().set(dev.hardrush.p0.monster.mixin.CreeperPoweredAccessor.hardrush$poweredData(), charged);
        selection.state.updateModel();
        for (ServerPlayer observer : PlayerLookup.tracking(player))
            sendRaw(observer, new ClientboundSetEntityDataPacket(player.getId(), selection.state.metadata));
    }

    private static State externalState(ServerPlayer observer, int entityId) {
        State state = BY_ENTITY.get(entityId);
        return state != null && !observer.getUUID().equals(state.owner.getUUID()) ? state : null;
    }

    private static void bind(ServerPlayer player, Selection selection) {
        Mob model = selection.type.create(player.level(), EntitySpawnReason.COMMAND);
        if (model == null) throw new IllegalStateException("Cannot create monster metadata template");
        State state = new State(player, selection.type, model);
        state.updateModel();
        selection.state = state;
        BY_ENTITY.put(player.getId(), state);
        for (ServerPlayer observer : PlayerLookup.tracking(player)) refreshObserver(observer, player, state);
    }

    private static void unbind(Selection selection) {
        State state = selection.state;
        if (state != null) BY_ENTITY.remove(state.realId, state);
        selection.state = null;
    }

    private static void refreshSkinIfSafe(ServerPlayer player, Selection selection) {
        if (selection.skinRefreshPending && OwnerSkin.canRefresh(player)) {
            OwnerSkin.steve(player);
            selection.skinRefreshPending = false;
        }
    }

    private static void refreshObserver(ServerPlayer observer, ServerPlayer player, State state) {
        if (observer.getUUID().equals(player.getUUID())) return;
        List<Packet<? super ClientGamePacketListener>> packets = new ArrayList<>();
        packets.add(new ClientboundRemoveEntitiesPacket(player.getId()));
        packets.add(new ClientboundAddEntityPacket(player.getId(), player.getUUID(),
            player.getX(), player.getY(), player.getZ(), player.getXRot(), player.getYRot(),
            state == null ? EntityTypes.PLAYER : state.type, 0, Vec3.ZERO, player.getYHeadRot()));
        List<SynchedEntityData.DataValue<?>> data = state == null
            ? player.getEntityData().getNonDefaultValues() : state.metadata;
        if (data != null && !data.isEmpty()) packets.add(new ClientboundSetEntityDataPacket(player.getId(), data));
        packets.add(state == null ? new ClientboundUpdateAttributesPacket(player.getId(),
            player.getAttributes().getSyncableAttributes()) : state.attributes());
        List<Pair<EquipmentSlot, net.minecraft.world.item.ItemStack>> equipment = new ArrayList<>();
        for (EquipmentSlot slot : EquipmentSlot.values()) equipment.add(Pair.of(slot, MonsterRoles.actualEquipment(player,slot)));
        packets.add(new ClientboundSetEquipmentPacket(player.getId(), equipment));
        if (player.isPassenger()) packets.add(new net.minecraft.network.protocol.game.ClientboundSetPassengersPacket(player.getVehicle()));
        sendRaw(observer, new ClientboundBundlePacket(packets));
    }

    static void sendRaw(ServerPlayer player, Packet<?> packet) {
        boolean previous = RAW_SEND.get();
        RAW_SEND.set(true);
        try { player.connection.send(packet); }
        finally { RAW_SEND.set(previous); }
    }

    @SuppressWarnings("unchecked")
    private static Packet<? super ClientGamePacketListener> asGamePacket(Packet<?> packet) {
        return (Packet<? super ClientGamePacketListener>) packet;
    }

    private static void requireMainThread(ServerPlayer player) {
        if (!player.level().getServer().isSameThread()) throw new IllegalStateException("Disguise operations require the server thread");
    }

    private static final class Selection {
        final String kind;
        final EntityType<? extends Mob> type;
        State state;
        boolean skinRefreshPending;
        Selection(String kind, EntityType<? extends Mob> type) { this.kind = kind; this.type = type; }
    }

    private static final class State {
        final ServerPlayer owner;
        final ServerLevel level;
        final int realId;
        final EntityType<? extends Mob> type;
        final Mob model;
        final Map<Integer, SynchedEntityData.DataValue<?>> latestValues = new LinkedHashMap<>();
        volatile List<SynchedEntityData.DataValue<?>> metadata = List.of();
        volatile List<Pair<EquipmentSlot,net.minecraft.world.item.ItemStack>> equipment=List.of();
        State(ServerPlayer owner, EntityType<? extends Mob> type, Mob model) {
            this.owner = owner; this.level = owner.level(); this.realId = owner.getId();
            this.type = type; this.model = model;
            merge(model.getEntityData().getNonDefaultValues());
        }
        void updateModel() {
            equipment=java.util.Arrays.stream(EquipmentSlot.values()).map(slot->Pair.of(slot,MonsterRoles.actualEquipment(owner,slot))).toList();
            model.setPose(owner.getPose());
            model.setShiftKeyDown(owner.isShiftKeyDown());
            model.setSprinting(owner.isSprinting());
            model.setSwimming(owner.isSwimming());
            model.setSharedFlagOnFire(owner.isOnFire());
            model.setInvisible(owner.isInvisible());
            model.setGlowingTag(owner.isCurrentlyGlowing());
            model.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(owner.getMaxHealth());
            model.setHealth(owner.getHealth());
            model.setLeftHanded(owner.getMainArm() == net.minecraft.world.entity.HumanoidArm.LEFT);
            model.setAggressive(owner.isUsingItem());
            merge(model.getEntityData().packDirty());
            metadata = List.copyOf(latestValues.values());
        }
        ClientboundUpdateAttributesPacket attributes() {
            var attributes = owner.getAttributes().getSyncableAttributes().stream()
                .filter(attribute -> model.getAttributes().hasAttribute(attribute.getAttribute())).toList();
            return new ClientboundUpdateAttributesPacket(realId, attributes);
        }
        void merge(List<SynchedEntityData.DataValue<?>> values) {
            if (values != null) for (var value : values) latestValues.put(value.id(), value);
        }
    }
}
