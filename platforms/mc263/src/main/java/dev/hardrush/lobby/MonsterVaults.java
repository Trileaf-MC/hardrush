package dev.hardrush.lobby;

import dev.hardrush.core.lobby.LobbyLayout;
import dev.hardrush.core.round.MonsterCoins;
import dev.hardrush.core.round.MonsterRole;
import dev.hardrush.core.round.ShopOffer;
import dev.hardrush.monster.MonsterGacha;
import dev.hardrush.world.LobbyWorld;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.VaultBlock;
import net.minecraft.world.level.block.entity.vault.VaultSharedData;
import net.minecraft.world.level.block.entity.vault.VaultState;
import net.minecraft.world.phys.Vec3;

/**
 * The camp's two native vaults. Their own server ticking is disabled in the lobby; each camp
 * monster instead receives its own display cycle and opening animation as packets, while the
 * drawn equipment is committed to the wallet before anything is shown.
 */
public final class MonsterVaults {
    private static final Map<MinecraftServer, MonsterVaults> INSTANCES = new IdentityHashMap<>();
    private static final double RANGE = 4.5;
    private static final int CYCLE_TICKS = 20, UNLOCK_TICKS = 14, EJECT_TICKS = 20, PICKUP_TICKS = 20;

    private final RevivalTotems.Access access;
    private final RandomSource random = RandomSource.create();
    private final Map<UUID, Map<ShopOffer, Boolean>> shown = new HashMap<>();
    private final Map<UUID, Opening> openings = new HashMap<>();

    private static final class Opening {
        final ServerPlayer player; final ShopOffer key; final ItemStack item; final int started;
        int entityId = -1; boolean committed;
        Opening(ServerPlayer player, ShopOffer key, ItemStack item, int started) {
            this.player = player; this.key = key; this.item = item; this.started = started;
        }
    }

    private MonsterVaults(RevivalTotems.Access access) { this.access = access; }

    static void configure(MinecraftServer server, RevivalTotems.Access access) { INSTANCES.put(server, new MonsterVaults(access)); }
    static void unconfigure(MinecraftServer server) { INSTANCES.remove(server); }

    public static LobbyLayout.Facility facility(ShopOffer key) {
        return key == ShopOffer.OMINOUS_KEY ? LobbyLayout.Facility.OMINOUS_VAULT : LobbyLayout.Facility.VAULT;
    }

    private static BlockPos position(MinecraftServer server, ShopOffer key) {
        return BlockPos.containing(LobbyWorld.open(server).layoutPosition(LobbyLayout.facility(facility(key))));
    }

    /** A main-hand right click on a vault, already validated by the lobby router. */
    public static void use(ServerPlayer player, ShopOffer key) {
        var vaults = INSTANCES.get(player.level().getServer());
        if (vaults != null) vaults.open(player, key);
    }

    private void open(ServerPlayer player, ShopOffer key) {
        var server = player.level().getServer();
        var pos = position(server, key);
        var r = access.committed().orElse(null);
        var p = r == null ? null : r.participants().get(player.getUUID());
        if (p == null || !MonsterCoins.enabled(r.rules()) || !access.eligible(player)) {
            tell(player, "宝库只为极限挑战中营地待命的怪物开启。"); fail(player, pos); return;
        }
        if (LobbyRuntime.revivalFirst(player, r, p)) { fail(player, pos); return; }
        if (p.monsterRole() == MonsterRole.CREEPER || !p.wallet().roleChosen()) {
            tell(player, "先在展位选择僵尸或骷髅，再用试炼钥匙开启宝库。"); fail(player, pos); return;
        }
        if (!CampKit.holdsKey(player, key)) {
            tell(player, "需要手持" + key.title() + "：向营地的女巫商人购买。"); fail(player, pos); return;
        }
        if (openings.containsKey(p.id()) || access.busy(p.id()) || access.session().mutationPending()) {
            tell(player, "宝库正在开启，请稍候。"); return;
        }
        var draw = MonsterGacha.draw(player.level(), random, p.monsterRole(), key);
        var opening = new Opening(player, key, draw.stack().copy(), server.getTickCount());
        openings.put(p.id(), opening);
        CampKit.hold(player, true);
        access.session().requestVaultOpen(r.roundId(), p.id(), p.lifeSequence(), p.monsterDeploymentSequence(), key,
                draw.slot(), draw.roles(), MonsterGacha.encode(player.level().registryAccess(), draw.stack()), reply -> {
                    if (!reply.success()) {
                        openings.remove(p.id()); CampKit.hold(player, false);
                        tell(player, "宝库未能开启，钥匙已保留：" + reply.message()); fail(player, pos); return;
                    }
                    opening.committed = true;
                    sound(player, pos, SoundEvents.VAULT_INSERT_ITEM);
                    state(player, pos, key, VaultState.UNLOCKING, opening.item);
                });
    }

    public static void tick(MinecraftServer server) {
        var vaults = INSTANCES.get(server);
        if (vaults != null && LobbyWorld.open(server).ready()) vaults.update(server);
    }

    private void update(MinecraftServer server) {
        int now = server.getTickCount();
        for (var opening : List.copyOf(openings.values())) animate(server, opening, now);
        var r = access.committed().orElse(null);
        for (var player : server.getPlayerList().getPlayers()) {
            if (openings.containsKey(player.getUUID())) continue;
            var p = r == null ? null : r.participants().get(player.getUUID());
            boolean eligible = p != null && MonsterCoins.enabled(r.rules()) && LobbyWorld.isLobby(player.level())
                    && access.eligible(player) && p.wallet().roleChosen() && p.monsterRole() != MonsterRole.CREEPER;
            var states = shown.computeIfAbsent(player.getUUID(), ignored -> new EnumMap<>(ShopOffer.class));
            for (var key : List.of(ShopOffer.TRIAL_KEY, ShopOffer.OMINOUS_KEY)) {
                var pos = position(server, key);
                boolean active = eligible && player.position().distanceToSqr(Vec3.atCenterOf(pos)) <= RANGE * RANGE;
                boolean was = Boolean.TRUE.equals(states.get(key));
                if (active && (!was || (now + key.ordinal() * 7) % CYCLE_TICKS == 0)) {
                    // The cycle previews this monster's own pool, the same way native vaults preview their loot.
                    var preview = MonsterGacha.preview(random, p.monsterRole(), key);
                    if (!was) player.connection.send(new ClientboundLevelEventPacket(3015, pos, key == ShopOffer.OMINOUS_KEY ? 1 : 0, false));
                    state(player, pos, key, VaultState.ACTIVE, preview);
                } else if (!active && was) {
                    state(player, pos, key, VaultState.INACTIVE, ItemStack.EMPTY);
                }
                states.put(key, active);
            }
        }
        shown.keySet().removeIf(id -> server.getPlayerList().getPlayer(id) == null);
    }

    private void animate(MinecraftServer server, Opening opening, int now) {
        var player = opening.player;
        var pos = position(server, opening.key);
        if (server.getPlayerList().getPlayer(player.getUUID()) != player || !LobbyWorld.isLobby(player.level())) {
            openings.remove(player.getUUID()); CampKit.hold(player, false); return;
        }
        if (!opening.committed) return;
        int age = now - opening.started;
        if (age == UNLOCK_TICKS) {
            sound(player, pos, SoundEvents.VAULT_OPEN_SHUTTER);
            state(player, pos, opening.key, VaultState.EJECTING, opening.item);
        } else if (age == UNLOCK_TICKS + EJECT_TICKS) {
            var item = new ItemEntity(player.level(), pos.getX() + .5, pos.getY() + 1.1, pos.getZ() + .5, opening.item.copy());
            item.setDeltaMovement(0, .2, 0);
            opening.entityId = item.getId();
            player.connection.send(new ClientboundAddEntityPacket(item.getId(), item.getUUID(), item.getX(), item.getY(), item.getZ(),
                    0, 0, item.getType(), 0, item.getDeltaMovement(), 0));
            player.connection.send(new ClientboundSetEntityDataPacket(item.getId(), Objects.requireNonNull(item.getEntityData().getNonDefaultValues())));
            player.connection.send(new ClientboundLevelEventPacket(3017, pos, 0, false));
            sound(player, pos, SoundEvents.VAULT_EJECT_ITEM);
        } else if (age >= UNLOCK_TICKS + EJECT_TICKS + PICKUP_TICKS) {
            if (opening.entityId >= 0) {
                player.connection.send(new ClientboundTakeItemEntityPacket(opening.entityId, player.getId(), opening.item.getCount()));
                player.connection.send(new ClientboundRemoveEntitiesPacket(opening.entityId));
            }
            sound(player, pos, SoundEvents.VAULT_CLOSE_SHUTTER);
            state(player, pos, opening.key, VaultState.ACTIVE, ItemStack.EMPTY);
            openings.remove(player.getUUID());
            CampKit.hold(player, false);
            CampKit.refresh(player);
            tell(player, "宝库抽出了：" + opening.item.getHoverName().getString() + "，下次出场穿戴，只用一次。");
        }
    }

    /** Only this observer's client sees the state; the shared block stays inactive for everyone else. */
    private static void state(ServerPlayer player, BlockPos pos, ShopOffer key, VaultState state, ItemStack display) {
        var level = player.level();
        var block = level.getBlockState(pos);
        if (!block.hasProperty(VaultBlock.STATE) || !(level.getBlockEntity(pos) instanceof net.minecraft.world.level.block.entity.vault.VaultBlockEntity entity)) return;
        player.connection.send(new ClientboundBlockUpdatePacket(pos, block.setValue(VaultBlock.STATE, state)));
        var shared = new VaultSharedData(display, state == VaultState.INACTIVE ? Set.of() : Set.of(player.getUUID()), RANGE);
        player.connection.send(ClientboundBlockEntityDataPacket.create(entity, (ignored, registries) -> {
            var tag = new CompoundTag();
            tag.store(VaultSharedData.TAG_NAME, VaultSharedData.CODEC, registries.createSerializationContext(NbtOps.INSTANCE), shared);
            return tag;
        }));
    }

    private static void fail(ServerPlayer player, BlockPos pos) { sound(player, pos, SoundEvents.VAULT_INSERT_ITEM_FAIL); }

    private static void sound(ServerPlayer player, BlockPos pos, SoundEvent sound) {
        player.level().playSound(null, pos, sound, SoundSource.BLOCKS, 1, 1);
    }

    private static void tell(ServerPlayer player, String message) {
        if (player.connection != null) player.sendSystemMessage(Component.literal(message));
    }

    public static void disconnected(ServerPlayer player) {
        var vaults = INSTANCES.get(player.level().getServer());
        if (vaults == null) return;
        vaults.shown.remove(player.getUUID());
        var opening = vaults.openings.get(player.getUUID());
        if (opening != null && opening.player == player) vaults.openings.remove(player.getUUID());
        CampKit.hold(player, false);
    }
}
