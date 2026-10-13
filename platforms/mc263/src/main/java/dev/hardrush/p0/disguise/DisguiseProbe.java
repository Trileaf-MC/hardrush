package dev.hardrush.p0.disguise;

import com.mojang.datafixers.util.Pair;
import dev.hardrush.p0.disguise.mixin.DisguiseEntityAccessor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundHurtAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import net.minecraft.network.protocol.game.ClientboundSwingAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.network.syncher.EntityDataAccessor;
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
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.Team;

/**
 * An intentionally limited, server-only 26.3 packet probe. No fake entity is
 * inserted into a ServerLevel; the real player remains the only combat target.
 *
 * <p>Full-size self-view is a separate client entity plus a self-only invisible
 * player flag. This proves packet construction, not F5 rendering or first-person
 * usability. Equipment is rejected instead of editing the client's inventory to
 * conceal armor. No third-party disguise source is embedded here.</p>
 */
public final class DisguiseProbe {
    private static final int INVISIBLE_MASK = 1 << 5;
    private static final Map<UUID, State> ACTIVE = new ConcurrentHashMap<>();
    private static final Map<Integer, State> BY_ENTITY = new ConcurrentHashMap<>();
    private static final Map<UUID, Resume> RESUME = new HashMap<>();
    private static final ThreadLocal<Boolean> RAW_SEND = ThreadLocal.withInitial(() -> false);

    private DisguiseProbe() {}

    public static String apply(ServerPlayer player, String requestedType) {
        return apply(player, requestedType, true);
    }

    private static String apply(ServerPlayer player, String requestedType, boolean selfVisible) {
        requireMainThread(player);
        String name = requestedType.toLowerCase(Locale.ROOT);
        EntityType<? extends Mob> type = switch (name) {
            case "zombie" -> EntityTypes.ZOMBIE;
            case "skeleton" -> EntityTypes.SKELETON;
            case "creeper" -> EntityTypes.CREEPER;
            default -> null;
        };
        if (type == null) return "拒绝：P0 只接受 zombie、skeleton、creeper。";
        if (!player.isAlive() || player.isSpectator() || player.isPassenger()) {
            return "拒绝：请在存活、非旁观且未乘坐实体时运行外观原型。";
        }
        if (!hasEmptyEquipment(player)) {
            return "拒绝：P0 自身外观测试须双手、盔甲与其他装备槽全空；原物品未修改。装备外观尚未实现。";
        }

        clear(player);
        Mob model = type.create(player.level(), EntitySpawnReason.COMMAND);
        if (model == null) return "拒绝：当前世界无法创建所选原版实体的协议模型。";
        model.setNoAi(true);
        model.setNoGravity(true);
        model.setSilent(true);
        State state = new State(player, name, type, model);
        state.selfVisible = selfVisible;
        state.updateModel();
        ACTIVE.put(player.getUUID(), state);
        BY_ENTITY.put(player.getId(), state);

        for (ServerPlayer observer : PlayerLookup.tracking(player)) {
            refreshObserver(observer, player, state);
        }
        spawnSelf(state);
        return "已启用空装 P0 外观原型。" + status(player)
            + "；真实 F5、第一人称、碰撞、防作弊和回放尚需验收。";
    }

    public static void clear(ServerPlayer player) {
        requireMainThread(player);
        RESUME.remove(player.getUUID());
        State state = ACTIVE.remove(player.getUUID());
        if (state == null) return;
        BY_ENTITY.remove(state.realId, state);
        removeSelf(player, state);
        // The map is already empty, so native player metadata will pass through.
        for (ServerPlayer observer : PlayerLookup.tracking(player)) {
            refreshObserver(observer, player, null);
        }
    }

    public static void onDisconnect(ServerPlayer player) {
        requireMainThread(player);
        RESUME.remove(player.getUUID());
        State state = ACTIVE.remove(player.getUUID());
        if (state != null) BY_ENTITY.remove(state.realId, state);
        // Vanilla removes the real player from observers. The disconnecting
        // client's world and client-only team are discarded with its connection.
    }

    public static void tick(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("Disguise tick must run on server thread");
        for (State state : List.copyOf(ACTIVE.values())) {
            ServerPlayer player = server.getPlayerList().getPlayer(state.owner.getUUID());
            if (player == null) {
                ACTIVE.remove(state.owner.getUUID(), state);
                BY_ENTITY.remove(state.realId, state);
                continue;
            }
            if (!player.isAlive() || player.isSpectator() || player.isPassenger() || !hasEmptyEquipment(player)) {
                clear(player);
                player.sendSystemMessage(Component.literal("P0 外观已安全解除：死亡、旁观、乘坐或装备发生变化。物品未修改。"));
                continue;
            }
            if (player != state.owner || player.level() != state.level) {
                String type = state.name;
                clear(player);
                RESUME.put(player.getUUID(), new Resume(type, state.selfVisible, server.getTickCount() + 10));
                continue;
            }

            List<SynchedEntityData.DataValue<?>> previous = state.metadata;
            state.updateModel();
            if (state.selfVisible) {
                sendRaw(player, ClientboundEntityPositionSyncPacket.of(state.model));
                sendRaw(player, new ClientboundRotateHeadPacket(state.model, angle(player.getYHeadRot())));
            }
            if (!previous.equals(state.metadata)) {
                if (state.selfVisible) {
                    sendRaw(player, new ClientboundSetEntityDataPacket(state.model.getId(), state.metadata));
                }
                for (ServerPlayer observer : PlayerLookup.tracking(player)) {
                    sendRaw(observer, new ClientboundSetEntityDataPacket(state.realId, state.metadata));
                }
            }
            // 26.3 uses the new SwingAnimation packet, not old Animate action 0.
            float swing = player.getSwingAnimation(0);
            if (state.selfVisible && player.isSwinging() && (!state.wasSwinging || swing < state.lastSwing)) {
                var description = player.getCurrentSwing();
                if (description != null) {
                    sendRaw(player, new ClientboundSwingAnimationPacket(
                        state.model.getId(), description.hand(), description.animation()));
                }
            }
            state.wasSwinging = player.isSwinging();
            state.lastSwing = swing;
            if (state.selfVisible && player.hurtTime > state.lastHurtTime) {
                sendRaw(player, new ClientboundHurtAnimationPacket(state.model.getId(), player.getYRot()));
            }
            state.lastHurtTime = player.hurtTime;
            if (server.getTickCount() % 20 == 0) {
                // Other scoreboard users may have reassigned this client's own
                // entry. Restore our client-only hide/collision policy periodically.
                sendRaw(player, ClientboundSetPlayerTeamPacket.createPlayerPacket(
                    state.selfTeam, player.getScoreboardName(), ClientboundSetPlayerTeamPacket.Action.ADD));
                sendRaw(player, ownFlags(player, true));
            }
        }
        for (var entry : List.copyOf(RESUME.entrySet())) {
            if (server.getTickCount() < entry.getValue().tick()) continue;
            RESUME.remove(entry.getKey());
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player != null) player.sendSystemMessage(Component.literal(
                apply(player, entry.getValue().name(), entry.getValue().selfVisible())));
        }
    }

    /** Explicit fallback probe; vanilla does not report F5 changes to the server. */
    public static String setSelfView(ServerPlayer player, String mode) {
        requireMainThread(player);
        if (!mode.equals("visible") && !mode.equals("hidden")) {
            return "拒绝：selfview 只接受 visible 或 hidden。";
        }
        boolean visible = mode.equals("visible");
        State state = ACTIVE.get(player.getUUID());
        if (state == null) {
            Resume resume = RESUME.get(player.getUUID());
            if (resume == null) return "拒绝：请先启用 P0 伪装。";
            RESUME.put(player.getUUID(), new Resume(resume.name(), visible, resume.tick()));
            return "已设置恢复后的自身显示：" + mode;
        }
        if (state.selfVisible != visible) {
            state.selfVisible = visible;
            if (visible) {
                state.updateModel();
                List<Packet<? super ClientGamePacketListener>> packets = new ArrayList<>();
                addSelfBodyPackets(state, packets);
                sendRaw(player, new ClientboundBundlePacket(packets));
            } else {
                sendRaw(player, new ClientboundRemoveEntitiesPacket(state.model.getId()));
            }
        }
        return "已切换实验性自身显示。" + status(player)
            + "；hidden 供第一人称操作，visible 供 F5 外观预览；这不是自动 F5 方案。";
    }

    /** Main-thread diagnostic intended for both operators and packet smoke tests. */
    public static String status(ServerPlayer player) {
        State state = ACTIVE.get(player.getUUID());
        if (state == null) return "disguise=off; realId=" + player.getId();
        return "disguise=" + state.name + "; realId=" + state.realId
            + "; selfId=" + state.model.getId() + "; selfUuid=" + state.model.getUUID()
            + "; metadataFields=" + state.metadata.size()
            + "; selfView=" + (state.selfVisible ? "visible" : "hidden")
            + "; selfSpawned=" + state.selfVisible
            + "; selfContainsEye=" + state.model.getBoundingBox().contains(player.getEyePosition())
            + "; pickableFromInside=" + state.model.canBePickedFromInside()
            + "; physics=player; equipment=empty-only; selfTargeting="
            + (state.selfVisible ? "unresolved" : "self-not-spawned") + "; visualAcceptance=pending";
    }

    /** Called by the outbound mixin; never changes the original packet object. */
    public static Packet<?> rewrite(ServerPlayer observer, Packet<?> packet) {
        if (RAW_SEND.get() || ACTIVE.isEmpty()) return packet;
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
            State state = BY_ENTITY.get(spawn.getId());
            if (state != null && observer.getId() != state.realId) {
                return new ClientboundAddEntityPacket(spawn.getId(), spawn.getUUID(),
                    spawn.getX(), spawn.getY(), spawn.getZ(), spawn.getXRot(), spawn.getYRot(),
                    state.type, 0, spawn.getMovement(), spawn.getYHeadRot());
            }
        } else if (packet instanceof ClientboundSetEntityDataPacket data) {
            State state = BY_ENTITY.get(data.id());
            if (state != null) {
                if (observer.getId() == state.realId) return hideOwnFlags(data);
                return new ClientboundSetEntityDataPacket(data.id(), state.metadata);
            }
        } else if (packet instanceof ClientboundUpdateAttributesPacket attributes) {
            State state = BY_ENTITY.get(attributes.getEntityId());
            if (state != null && observer.getId() != state.realId) return state.externalAttributes;
        }
        return packet;
    }

    private static boolean hasEmptyEquipment(ServerPlayer player) {
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (!player.getItemBySlot(slot).isEmpty()) return false;
        }
        return true;
    }

    private static void refreshObserver(ServerPlayer observer, ServerPlayer player, State state) {
        if (observer == player) return;
        List<Packet<? super ClientGamePacketListener>> packets = new ArrayList<>();
        packets.add(new ClientboundRemoveEntitiesPacket(player.getId()));
        packets.add(spawn(player.getId(), player.getUUID(), player, state == null ? EntityTypes.PLAYER : state.type));
        List<SynchedEntityData.DataValue<?>> data = state == null
            ? player.getEntityData().getNonDefaultValues() : state.metadata;
        if (data != null && !data.isEmpty()) packets.add(new ClientboundSetEntityDataPacket(player.getId(), data));
        packets.add(new ClientboundUpdateAttributesPacket(player.getId(),
            state == null ? player.getAttributes().getSyncableAttributes() : state.model.getAttributes().getSyncableAttributes()));
        List<Pair<EquipmentSlot, net.minecraft.world.item.ItemStack>> equipment = new ArrayList<>();
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            equipment.add(Pair.of(slot, player.getItemBySlot(slot).copy()));
        }
        packets.add(new ClientboundSetEquipmentPacket(player.getId(), equipment));
        sendRaw(observer, new ClientboundBundlePacket(packets));
    }

    private static void spawnSelf(State state) {
        ServerPlayer player = state.owner;
        List<Packet<? super ClientGamePacketListener>> packets = new ArrayList<>();
        packets.add(ClientboundSetPlayerTeamPacket.createAddOrModifyPacket(state.selfTeam, true));
        packets.add(ownFlags(player, true));
        if (state.selfVisible) addSelfBodyPackets(state, packets);
        sendRaw(player, new ClientboundBundlePacket(packets));
    }

    private static void addSelfBodyPackets(State state, List<Packet<? super ClientGamePacketListener>> packets) {
        packets.add(spawn(state.model.getId(), state.model.getUUID(), state.owner, state.type));
        packets.add(new ClientboundSetEntityDataPacket(state.model.getId(), state.metadata));
        packets.add(new ClientboundUpdateAttributesPacket(state.model.getId(), state.model.getAttributes().getSyncableAttributes()));
    }

    private static void removeSelf(ServerPlayer player, State state) {
        sendRaw(player, new ClientboundRemoveEntitiesPacket(state.model.getId()));
        sendRaw(player, ClientboundSetPlayerTeamPacket.createRemovePacket(state.selfTeam));
        // Restore the current authoritative team, including changes made during
        // the probe. No actual scoreboard or player's equipment was modified.
        PlayerTeam actualTeam = player.getTeam();
        if (actualTeam != null) {
            sendRaw(player, ClientboundSetPlayerTeamPacket.createPlayerPacket(
                actualTeam, player.getScoreboardName(), ClientboundSetPlayerTeamPacket.Action.ADD));
        }
        sendRaw(player, ownFlags(player, false));
    }

    private static ClientboundAddEntityPacket spawn(int id, UUID uuid, ServerPlayer player, EntityType<?> type) {
        return new ClientboundAddEntityPacket(id, uuid, player.getX(), player.getY(), player.getZ(),
            player.getXRot(), player.getYRot(), type, 0, Vec3.ZERO, player.getYHeadRot());
    }

    private static ClientboundSetEntityDataPacket ownFlags(ServerPlayer player, boolean hidden) {
        EntityDataAccessor<Byte> accessor = DisguiseEntityAccessor.hardrush$sharedFlags();
        byte flags = player.getEntityData().get(accessor);
        if (hidden) flags = (byte) (flags | INVISIBLE_MASK);
        return new ClientboundSetEntityDataPacket(player.getId(), List.of(SynchedEntityData.DataValue.create(accessor, flags)));
    }

    private static ClientboundSetEntityDataPacket hideOwnFlags(ClientboundSetEntityDataPacket packet) {
        EntityDataAccessor<Byte> accessor = DisguiseEntityAccessor.hardrush$sharedFlags();
        List<SynchedEntityData.DataValue<?>> values = new ArrayList<>(packet.packedItems());
        for (int index = 0; index < values.size(); index++) {
            SynchedEntityData.DataValue<?> value = values.get(index);
            if (value.id() == accessor.id() && value.value() instanceof Byte flags) {
                values.set(index, SynchedEntityData.DataValue.create(accessor, (byte) (flags | INVISIBLE_MASK)));
            }
        }
        // When the update doesn't include flags, the previously sent hidden
        // value remains valid; do not invent a zero baseline for unrelated data.
        return new ClientboundSetEntityDataPacket(packet.id(), values);
    }

    private static void sendRaw(ServerPlayer player, Packet<?> packet) {
        boolean previous = RAW_SEND.get();
        RAW_SEND.set(true);
        try {
            player.connection.send(packet);
        } finally {
            RAW_SEND.set(previous);
        }
    }

    @SuppressWarnings("unchecked")
    private static Packet<? super ClientGamePacketListener> asGamePacket(Packet<?> packet) {
        // Only game packets arrive through a ClientboundBundlePacket.
        return (Packet<? super ClientGamePacketListener>) packet;
    }

    private static byte angle(float degrees) { return (byte) (degrees * 256.0F / 360.0F); }

    private static void requireMainThread(ServerPlayer player) {
        if (!player.level().getServer().isSameThread()) throw new IllegalStateException("Disguise operations require the server thread");
    }

    private record Resume(String name, boolean selfVisible, int tick) {}

    private static final class State {
        private final ServerPlayer owner;
        private final ServerLevel level;
        private final int realId;
        private final String name;
        private final EntityType<? extends Mob> type;
        private final Mob model;
        private final PlayerTeam selfTeam;
        private final Map<Integer, SynchedEntityData.DataValue<?>> latestValues = new LinkedHashMap<>();
        private volatile List<SynchedEntityData.DataValue<?>> metadata = List.of();
        private final ClientboundUpdateAttributesPacket externalAttributes;
        private boolean wasSwinging;
        private float lastSwing;
        private int lastHurtTime;
        private boolean selfVisible = true;

        private State(ServerPlayer owner, String name, EntityType<? extends Mob> type, Mob model) {
            this.owner = owner;
            this.level = owner.level();
            this.realId = owner.getId();
            this.name = name;
            this.type = type;
            this.model = model;
            this.selfTeam = new PlayerTeam(new Scoreboard(), "hrp0_" + model.getId());
            selfTeam.setCollisionRule(Team.CollisionRule.NEVER);
            selfTeam.setNameTagVisibility(Team.Visibility.NEVER);
            selfTeam.setSeeFriendlyInvisibles(false);
            selfTeam.getPlayers().add(owner.getScoreboardName());
            selfTeam.getPlayers().add(model.getUUID().toString());
            this.externalAttributes = new ClientboundUpdateAttributesPacket(realId, model.getAttributes().getSyncableAttributes());
            merge(model.getEntityData().getNonDefaultValues());
        }

        private void updateModel() {
            model.snapTo(owner.position(), owner.getYRot(), owner.getXRot());
            model.setYHeadRot(owner.getYHeadRot());
            model.setOnGround(owner.onGround());
            model.setDeltaMovement(Vec3.ZERO);
            model.setPose(owner.getPose());
            model.setShiftKeyDown(owner.isShiftKeyDown());
            model.setSprinting(owner.isSprinting());
            model.setSwimming(owner.isSwimming());
            model.setSharedFlagOnFire(owner.isOnFire());
            model.setGlowingTag(owner.isCurrentlyGlowing());
            model.setHealth(owner.getHealth());
            merge(model.getEntityData().packDirty());
            metadata = List.copyOf(latestValues.values());
        }

        private void merge(Collection<SynchedEntityData.DataValue<?>> changes) {
            if (changes != null) {
                for (SynchedEntityData.DataValue<?> value : changes) latestValues.put(value.id(), value);
            }
        }
    }
}
