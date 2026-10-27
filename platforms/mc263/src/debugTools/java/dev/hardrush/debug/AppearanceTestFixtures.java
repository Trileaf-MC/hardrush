package dev.hardrush.debug;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.hardrush.disguise.MonsterDisguise;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.UUID;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;

/** Isolated test actor, never packaged in the game mod. No camera experiments. */
public final class AppearanceTestFixtures implements ModInitializer {
    private static ServerPlayer actor;
    private static EmbeddedChannel channel;

    @Override public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) ->
            dispatcher.register(Commands.literal("hardrushdebug")
                .requires(Commands.hasPermission(Commands.LEVEL_OWNERS))
                .then(Commands.literal("snapshot")
                    .then(Commands.argument("player", EntityArgument.player())
                        .executes(context -> {
                            var player = EntityArgument.getPlayer(context, "player");
                            var data = new com.google.gson.JsonObject();
                            data.addProperty("name", player.getGameProfile().name());
                            data.addProperty("uuid", player.getUUID().toString());
                            data.addProperty("id", player.getId());
                            data.addProperty("instance", System.identityHashCode(player));
                            data.addProperty("dimension", player.level().dimension().identifier().toString());
                            data.addProperty("loaded", player.connection.hasClientLoaded());
                            data.addProperty("teleportPending", ((dev.hardrush.debug.mixin.LoadAcknowledgementAccessor) player.connection)
                                .hardrush$awaitingPosition() != null);
                            data.addProperty("alive", player.isAlive());
                            data.addProperty("health", player.getHealth());
                            data.addProperty("invisible", player.isInvisible());
                            data.addProperty("nativeCamera", player.getCamera() == player);
                            data.addProperty("inventoryMenu", player.containerMenu == player.inventoryMenu);
                            data.addProperty("team", player.getTeam() == null ? "" : player.getTeam().getName());
                            data.addProperty("permission", player.level().getServer().getProfilePermissions(
                                new net.minecraft.server.players.NameAndId(player.getGameProfile())).level().toString());
                            data.addProperty("gameMode", player.gameMode.getGameModeForPlayer().getName());
                            var attributes = new com.google.gson.JsonObject();
                            for (var attribute : player.getAttributes().getSyncableAttributes()) {
                                attributes.addProperty(attribute.getAttribute().unwrapKey().orElseThrow().identifier().toString(), attribute.getValue());
                            }
                            data.add("attributes", attributes);
                            var equipment = new com.google.gson.JsonObject();
                            for (var slot : EquipmentSlot.values()) {
                                var stack = player.getItemBySlot(slot);
                                equipment.addProperty(slot.getName(), net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
                            }
                            data.add("equipment", equipment);
                            int matchingEntities = 0;
                            for (var level : player.level().getServer().getAllLevels()) {
                                for (var entity : level.getAllEntities()) if (entity.getUUID().equals(player.getUUID())) matchingEntities++;
                            }
                            data.addProperty("matchingEntities", matchingEntities);
                            data.addProperty("appearance", MonsterDisguise.status(player));
                            data.addProperty("monster", dev.hardrush.monster.MonsterRoles.status(player));
                            context.getSource().sendSuccess(() -> Component.literal(data.toString()), false);
                            return 1;
                        })))
                .then(Commands.literal("actor")
                    .then(Commands.argument("kind", StringArgumentType.word())
                        .executes(context -> {
                            var server = context.getSource().getServer();
                            String kind = StringArgumentType.getString(context, "kind");
                            if (kind.equals("clear")) clear(server);
                            else if (kind.equals("cleanup")) {
                                // Retire only the old self-view test's tagged
                                // pig, which otherwise overlaps this fixture.
                                for (var entity : server.overworld().getAllEntities()) {
                                    if (entity.entityTags().contains("hr_selfview_target")) entity.discard();
                                }
                            }
                            else if (kind.equals("respawn")) {
                                if (actor == null) throw new IllegalStateException("Create the actor first");
                                actor = server.getPlayerList().respawn(actor, false, Entity.RemovalReason.KILLED);
                            } else {
                                if (!java.util.Set.of("zombie", "skeleton", "creeper").contains(kind)) {
                                    context.getSource().sendFailure(Component.literal("Unknown actor kind"));
                                    return 0;
                                }
                                var viewer = server.getPlayerList().getPlayerByName("HRDebug");
                                if (viewer == null) throw new IllegalStateException("Connect HRDebug first");
                                if (actor == null) {
                                    var profile = new GameProfile(UUID.fromString("989b216e-c951-4c81-b41c-7b19082677aa"), "HRVisualTarget");
                                    actor = new ServerPlayer(server, viewer.level(), profile, ClientInformation.createDefault());
                                    var connection = new SinkConnection();
                                    channel = new EmbeddedChannel(connection);
                                    server.getPlayerList().placeNewPlayer(connection, actor, CommonListenerCookie.createInitial(profile, false));
                                }
                                actor.setGameMode(GameType.SURVIVAL);
                                actor.teleportTo(viewer.level(), 0.5, 150, 2.5, java.util.Set.of(), 180, 0, true);
                                actor.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
                                actor.setHealth(20);
                                actor.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
                                actor.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.DIAMOND_HELMET));
                                MonsterDisguise.apply(actor, kind);
                                // This simulated connection has no graphical
                                // client to acknowledge loading. Complete the
                                // native handshake; otherwise vanilla protects
                                // this actor from all damage indefinitely.
                                actor.connection.handleAcceptPlayerLoad(new net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket());
                            }
                            context.getSource().sendSuccess(() -> Component.literal("Appearance test actor=" + kind), false);
                            return 1;
                        })))));
        ServerLifecycleEvents.SERVER_STOPPING.register(AppearanceTestFixtures::clear);
    }

    private static void clear(MinecraftServer server) {
        if (actor != null) {
            MonsterDisguise.clear(actor);
            server.getPlayerList().remove(actor);
            actor = null;
        }
        if (channel != null) { channel.finishAndReleaseAll(); channel = null; }
    }

    private static final class SinkConnection extends Connection {
        SinkConnection() { super(PacketFlow.SERVERBOUND); }
        @Override public void send(Packet<?> packet) {}
        @Override public void send(Packet<?> packet, ChannelFutureListener listener) {}
        @Override public void send(Packet<?> packet, ChannelFutureListener listener, boolean flush) {}
    }
}
