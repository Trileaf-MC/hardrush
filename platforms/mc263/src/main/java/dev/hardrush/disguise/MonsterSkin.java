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
import java.util.function.Function;
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

/**
 * Skin themed after each monster form, carried only by outgoing player-info packets; never
 * modifies the authenticated identity or properties. The owner sees it on their own player
 * model, and every player sees it as the owner's TAB head.
 */
public final class MonsterSkin {
    private static final Map<String, Property> SKINS = loadSkins();
    private static final Map<UUID, Boolean> RESTORE_PENDING = new ConcurrentHashMap<>();
    private static final java.util.Set<UUID> OVERRIDDEN = ConcurrentHashMap.newKeySet();
    private static final ThreadLocal<Batch> BATCH = new ThreadLocal<>();

    private MonsterSkin() {}

    static boolean canRefresh(ServerPlayer player) {
        return player.isAlive() && player.getCamera() == player && !player.isUsingItem()
            && !player.isPassenger() && !player.isSleeping()
            && player.containerMenu == player.inventoryMenu;
    }

    /** Shows the owner a skin themed after the monster form, on the native player model. */
    static void apply(ServerPlayer player, String kind) {
        RESTORE_PENDING.remove(player.getUUID());
        refresh(player, kind);
        OVERRIDDEN.add(player.getUUID());
    }

    static void restore(ServerPlayer player) {
        if (!OVERRIDDEN.contains(player.getUUID())) return;
        if (canRefresh(player)) {
            refresh(player, null);
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
                refresh(player, null);
                RESTORE_PENDING.remove(uuid);
                OVERRIDDEN.remove(uuid);
            }
        }
    }

    /**
     * Gives every disguised player's entry in an ADD_PLAYER packet the skin of their form.
     * {@code kindOf} maps a profile id to its monster form, or null when it is not disguised.
     */
    static Packet<?> rewrite(ServerPlayer recipient, Packet<?> packet, Function<UUID, String> kindOf) {
        if (!(packet instanceof ClientboundPlayerInfoUpdatePacket info)
                || !info.actions().contains(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER)) return packet;
        boolean changed = false;
        var entries = new java.util.ArrayList<ClientboundPlayerInfoUpdatePacket.Entry>(info.entries().size());
        for (var entry : info.entries()) {
            String kind = kindOf.apply(entry.profileId());
            if (kind == null) { entries.add(entry); continue; }
            entries.add(withSkin(entry, skin(kind)));
            changed = true;
            if (entry.profileId().equals(recipient.getUUID())) OVERRIDDEN.add(recipient.getUUID());
        }
        if (!changed) return packet;
        var replacement = new ClientboundPlayerInfoUpdatePacket(info.actions().clone(), List.of());
        ((PlayerInfoPacketAccessor) replacement).hardrush$entries(List.copyOf(entries));
        return replacement;
    }

    /**
     * Re-adds {@code player}'s TAB entry on every other client with the skin of {@code kind}, or
     * the authenticated one when null. Vanilla only adds an entry that is absent, so it is removed
     * first; both go in one bundle, so the list never shows the player missing. Clients cache the
     * entry on a player entity, so this must precede re-spawning {@code player} as a player.
     */
    static void showToOthers(ServerPlayer player, String kind) {
        Packet<?> info = ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(player));
        if (kind != null) info = rewrite(player, info, id -> id.equals(player.getUUID()) ? kind : null);
        @SuppressWarnings("unchecked")
        var refresh = new ClientboundBundlePacket(List.of(
            new ClientboundPlayerInfoRemovePacket(List.of(player.getUUID())),
            (Packet<? super ClientGamePacketListener>) info));
        for (ServerPlayer other : player.level().getServer().getPlayerList().getPlayers()) {
            if (other != player) MonsterDisguise.sendRaw(other, refresh);
        }
    }

    private static ClientboundPlayerInfoUpdatePacket.Entry withSkin(ClientboundPlayerInfoUpdatePacket.Entry entry, Property skin) {
        var properties = ImmutableMultimap.<String, Property>builder();
        entry.profile().properties().entries().stream().filter(property -> !property.getKey().equals("textures"))
            .forEach(property -> properties.put(property.getKey(), property.getValue()));
        properties.put("textures", skin);
        var profile = new GameProfile(entry.profile().id(), entry.profile().name(), new PropertyMap(properties.build()));
        return new ClientboundPlayerInfoUpdatePacket.Entry(entry.profileId(), profile, entry.listed(), entry.latency(),
            entry.gameMode(), entry.displayName(), entry.showHat(), entry.listOrder(), entry.chatSession());
    }

    /** {@code kind} null restores the authenticated skin. */
    private static void refresh(ServerPlayer player, String kind) {
        if (BATCH.get() != null) throw new IllegalStateException("Nested cosmetic refresh");
        Batch batch = new Batch(player, new java.util.ArrayList<>());
        BATCH.set(batch);
        try {
        var info = ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(player));
        if (kind != null) info = (ClientboundPlayerInfoUpdatePacket) rewrite(player, info,
            id -> id.equals(player.getUUID()) ? kind : null);
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
        dev.hardrush.monster.MonsterMining.refreshInput(player);
    }

    @SuppressWarnings("unchecked")
    public static boolean capture(ServerPlayer player, Packet<?> packet) {
        Batch batch = BATCH.get();
        if (batch == null || batch.player != player) return false;
        batch.packets.add((Packet<? super ClientGamePacketListener>) packet);
        return true;
    }

    private record Batch(ServerPlayer player, java.util.List<Packet<? super ClientGamePacketListener>> packets) {}

    /**
     * A profile wearing the monster form's skin, for drawing its head in text (the witch's guide). It names no real
     * account. Text heads would accept an unsigned texture; the player list needs the pinned signature.
     */
    public static net.minecraft.world.item.component.ResolvableProfile head(String kind) {
        var properties = ImmutableMultimap.<String, Property>of("textures", skin(kind));
        var id = UUID.nameUUIDFromBytes(("hardrush:head:" + kind).getBytes(StandardCharsets.UTF_8));
        return net.minecraft.world.item.component.ResolvableProfile.createResolved(new GameProfile(id, kind, new PropertyMap(properties)));
    }

    private static Property skin(String kind) {
        Property skin = SKINS.get(kind);
        if (skin == null) throw new IllegalArgumentException("No skin for monster form " + kind);
        return skin;
    }

    private static Map<String, Property> loadSkins() {
        try (var input = MonsterSkin.class.getResourceAsStream("/hardrush/monster-skins.json")) {
            if (input == null) throw new IllegalStateException("Missing pinned monster skin texture properties");
            var skins = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8))
                .getAsJsonObject().getAsJsonObject("skins");
            var loaded = new java.util.HashMap<String, Property>();
            // Vanilla renders another player's skin only when Mojang signed it
            // (PlayerInfo.createSkinLookup requires a secure skin for anyone but
            // the local player), so TAB heads need the pinned signature. It signs
            // the texture payload only; the authenticated profile is unchanged.
            for (var entry : skins.entrySet()) {
                var skin = entry.getValue().getAsJsonObject();
                loaded.put(entry.getKey(), new Property("textures",
                    skin.get("value").getAsString(), skin.get("signature").getAsString()));
            }
            return Map.copyOf(loaded);
        } catch (java.io.IOException failure) { throw new IllegalStateException("Cannot read monster skin texture properties", failure); }
    }
}
