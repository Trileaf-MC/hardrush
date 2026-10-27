package dev.hardrush.disguise;

import com.google.common.collect.ImmutableMultimap;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import dev.hardrush.disguise.mixin.PlayerInfoPacketAccessor;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerAbilitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import net.minecraft.network.protocol.game.ClientboundSetExperiencePacket;
import net.minecraft.network.protocol.game.ClientboundUpdateMobEffectPacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.PositionMoveRotation;

/** Per-owner cosmetic profile; never modifies authenticated identity or properties. */
final class OwnerSkin {
    private static final Property STEVE = loadSteve();
    private static final Map<UUID, Boolean> RESTORE_PENDING = new ConcurrentHashMap<>();
    private static final java.util.Set<UUID> OVERRIDDEN = ConcurrentHashMap.newKeySet();
    private static final ThreadLocal<Batch> BATCH = new ThreadLocal<>();

    private OwnerSkin() {}

    static boolean canRefresh(ServerPlayer player) {
        return player.isAlive() && player.getCamera() == player && !player.isUsingItem()
            && !player.isPassenger() && !player.isSleeping()
            && player.containerMenu == player.inventoryMenu;
    }

    static void steve(ServerPlayer player) {
        RESTORE_PENDING.remove(player.getUUID());
        refresh(player, true);
        OVERRIDDEN.add(player.getUUID());
    }

    static void restore(ServerPlayer player) {
        if (!OVERRIDDEN.contains(player.getUUID())) return;
        if (canRefresh(player)) {
            refresh(player, false);
            OVERRIDDEN.remove(player.getUUID());
        }
        else RESTORE_PENDING.put(player.getUUID(), true);
    }

    static void cancelRestore(ServerPlayer player) { RESTORE_PENDING.remove(player.getUUID()); }
    static void onDisconnect(ServerPlayer player) {
        RESTORE_PENDING.remove(player.getUUID());
        OVERRIDDEN.remove(player.getUUID());
    }
    static void onJoin(ServerPlayer player) { RESTORE_PENDING.remove(player.getUUID()); }
    static void stop() { RESTORE_PENDING.clear(); OVERRIDDEN.clear(); }
    static void tick(MinecraftServer server) {
        for (UUID uuid : RESTORE_PENDING.keySet()) {
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player != null && canRefresh(player)) {
                refresh(player, false);
                RESTORE_PENDING.remove(uuid);
                OVERRIDDEN.remove(uuid);
            }
        }
    }

    static Packet<?> rewrite(ServerPlayer owner, Packet<?> packet, boolean disguised) {
        if (!disguised || !(packet instanceof ClientboundPlayerInfoUpdatePacket info)
                || !info.actions().contains(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER)) return packet;
        var entries = info.entries().stream().map(entry -> entry.profileId().equals(owner.getUUID())
            ? withSteve(entry) : entry).toList();
        if (entries.equals(info.entries())) return packet;
        OVERRIDDEN.add(owner.getUUID());
        var replacement = new ClientboundPlayerInfoUpdatePacket(info.actions().clone(), List.of());
        ((PlayerInfoPacketAccessor) replacement).hardrush$entries(entries);
        return replacement;
    }

    private static ClientboundPlayerInfoUpdatePacket.Entry withSteve(ClientboundPlayerInfoUpdatePacket.Entry entry) {
        var properties = ImmutableMultimap.<String, Property>builder();
        entry.profile().properties().entries().stream().filter(property -> !property.getKey().equals("textures"))
            .forEach(property -> properties.put(property.getKey(), property.getValue()));
        properties.put("textures", STEVE);
        var profile = new GameProfile(entry.profile().id(), entry.profile().name(), new PropertyMap(properties.build()));
        return new ClientboundPlayerInfoUpdatePacket.Entry(entry.profileId(), profile, entry.listed(), entry.latency(),
            entry.gameMode(), entry.displayName(), entry.showHat(), entry.listOrder(), entry.chatSession());
    }

    private static void refresh(ServerPlayer player, boolean steve) {
        if (BATCH.get() != null) throw new IllegalStateException("Nested cosmetic refresh");
        Batch batch = new Batch(player, new java.util.ArrayList<>());
        BATCH.set(batch);
        try {
        var info = ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(player));
        if (steve) info = (ClientboundPlayerInfoUpdatePacket) rewrite(player, info, true);
        MonsterDisguise.sendRaw(player, new ClientboundPlayerInfoRemovePacket(List.of(player.getUUID())));
        MonsterDisguise.sendRaw(player, info);
        // LocalPlayer caches PlayerInfo in 26.3. This replaces only the client's
        // player object: no server death, inventory reset or dimension change.
        MonsterDisguise.sendRaw(player, new ClientboundRespawnPacket(
            player.createCommonSpawnInfo(player.level()), ClientboundRespawnPacket.KEEP_ALL_DATA));
        player.connection.teleport(PositionMoveRotation.of(player), java.util.Set.of());
        // Includes vanilla LEVEL_CHUNKS_LOAD_START. Without it the refreshed
        // LocalPlayer has the right skin but remains on Downloading Terrain.
        player.level().getServer().getPlayerList().sendLevelInfo(player, player.level());
        MonsterDisguise.sendRaw(player, new ClientboundPlayerAbilitiesPacket(player.getAbilities()));
        MonsterDisguise.sendRaw(player, new ClientboundSetHealthPacket(player.getHealth(),
            player.getFoodData().getFoodLevel(), player.getFoodData().getSaturationLevel()));
        MonsterDisguise.sendRaw(player, new ClientboundSetExperiencePacket(
            player.experienceProgress, player.totalExperience, player.experienceLevel));
        for (var effect : player.getActiveEffects()) {
            MonsterDisguise.sendRaw(player, new ClientboundUpdateMobEffectPacket(player.getId(), effect, false));
        }
        player.level().getServer().getPlayerList().sendAllPlayerInfo(player);
        } finally { BATCH.remove(); }
        // Respawn constructs an empty LocalPlayer inventory. Deliver its full
        // inventory and selected slot in the same native client bundle, so a
        // render tick cannot send slot zero before the authoritative slot arrives.
        MonsterDisguise.sendRaw(player, new ClientboundBundlePacket(batch.packets));
    }

    @SuppressWarnings("unchecked")
    public static boolean capture(ServerPlayer player, Packet<?> packet) {
        Batch batch = BATCH.get();
        if (batch == null || batch.player != player) return false;
        batch.packets.add((Packet<? super ClientGamePacketListener>) packet);
        return true;
    }

    private record Batch(ServerPlayer player, java.util.List<Packet<? super ClientGamePacketListener>> packets) {}

    private static Property loadSteve() {
        try (var input = OwnerSkin.class.getResourceAsStream("/hardrush/steve-profile.json")) {
            if (input == null) throw new IllegalStateException("Missing pinned Steve texture property");
            var property = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8))
                .getAsJsonObject().getAsJsonObject("property");
            // Native local skins allow unsigned textures. This owner-only
            // cosmetic property neither changes auth nor asserts another
            // account's signature. Other players never receive it.
            return new Property("textures", property.get("value").getAsString());
        } catch (java.io.IOException failure) { throw new IllegalStateException("Cannot read Steve texture property", failure); }
    }
}
