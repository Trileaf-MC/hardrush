package dev.hardrush.lobby;

import com.mojang.datafixers.util.Pair;
import com.mojang.math.Transformation;
import dev.hardrush.core.lobby.LobbyContext;
import dev.hardrush.core.lobby.plan.LobbyFunction;
import dev.hardrush.core.lobby.plan.Point;
import dev.hardrush.world.LobbyWorld;
import dev.hardrush.world.mixin.BlockDisplayAccessor;
import dev.hardrush.world.mixin.DisplayAccessor;
import dev.hardrush.world.mixin.InteractionAccessor;
import dev.hardrush.world.mixin.TextDisplayAccessor;
import java.util.*;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Interaction;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Witch;
import net.minecraft.world.entity.monster.skeleton.Skeleton;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChainBlock;
import net.minecraft.world.level.block.VaultBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.TeamColor;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Courtyard models sent to each viewer as packets, without spawning entities in the lobby world. */
public final class LobbyRoleModels {
    enum CreeperState { NATIVE, LOCKED, UNLOCKED }
    record CreeperBooth(CreeperState state, int price) {
        static final CreeperBooth NATIVE = new CreeperBooth(CreeperState.NATIVE, 0);
    }

    private record Model(LobbyFunction function, Entity click, Entity nameTag, List<Entity> entities) {}
    private static final class Viewer {
        final ServerPlayer owner;
        final ServerGamePacketListenerImpl connection;
        final UUID round;
        final List<Point> creeperPoints;
        final Map<Integer, Model> models = new LinkedHashMap<>();
        CreeperBooth creeper;
        PlayerTeam goldTeam;
        boolean open;
        Viewer(ServerPlayer owner, UUID round, List<Point> creeperPoints, CreeperBooth creeper, boolean open) {
            this.owner = owner;
            this.connection = owner.connection;
            this.round = round;
            this.creeperPoints = creeperPoints;
            this.creeper = creeper;
            this.open = open;
        }
        void add(Model model) { models.put(model.click.getId(), model); }
    }

    private static final List<LobbyFunction> NATIVE_MODELS = List.of(LobbyFunction.ROLE_ZOMBIE, LobbyFunction.ROLE_SKELETON,
            LobbyFunction.MERCHANT);
    private static final String CREEPER_TEAM_PREFIX = "hardrush_creeper_booth_";
    private final LobbyInteractions router;
    private final Map<UUID, Viewer> displays = new HashMap<>();
    private static int lastNativeId = -1;

    LobbyRoleModels(LobbyInteractions router) { this.router = router; }

    /** Clickable IDs belong to this exact connection and round, like the human hunt projections. */
    public Map<LobbyFunction, List<Integer>> view(ServerPlayer observer) {
        router.requireThread();
        var display = displays.get(observer.getUUID());
        if (display == null || display.owner != observer || display.connection != observer.connection) return Map.of();
        var ids = new EnumMap<LobbyFunction, List<Integer>>(LobbyFunction.class);
        display.models.forEach((id, model) -> ids.computeIfAbsent(model.function, ignored -> new ArrayList<>()).add(id));
        ids.replaceAll((function, list) -> List.copyOf(list));
        return Collections.unmodifiableMap(ids);
    }

    void tick(MinecraftServer server) {
        router.requireThread();
        for (var display : List.copyOf(displays.values())) {
            if (!available(display.owner, display.round) || display.connection != display.owner.connection)
                remove(display.owner);
        }
        var round = router.round();
        boolean open = LobbyRuntime.courtyardOpen(server);
        boolean sparkle = server.getTickCount() % 40 == 0;
        for (var display : displays.values()) {
            var creeper = LobbyRuntime.creeperBooth(display.owner);
            boolean replace = display.creeper.state != creeper.state;
            if (display.open != open || !display.creeper.equals(creeper)) {
                for (var model : display.models.values()) {
                    if (replace && model.function == LobbyFunction.ROLE_CREEPER) continue;
                    if (display.open == open && model.function != LobbyFunction.ROLE_CREEPER) continue;
                    name(model, open, creeper);
                    metadata(model.nameTag, display.connection::send);
                }
            }
            display.open = open;
            if (replace) replaceCreeper(display, creeper);
            else if (sparkle && creeper.state == CreeperState.UNLOCKED) {
                for (var point : display.creeperPoints) display.connection.send(particles(point, false));
            }
            display.creeper = creeper;
        }
        for (var observer : server.getPlayerList().getPlayers()) {
            if (!available(observer, round) || displays.containsKey(observer.getUUID())) continue;
            var plan = LobbyWorld.open(server).plan();
            var points = plan.models(LobbyFunction.ROLE_CREEPER).stream().map(placed -> placed.point()).toList();
            var display = new Viewer(observer, round, points, LobbyRuntime.creeperBooth(observer), open);
            for (var function : NATIVE_MODELS) for (var placed : plan.models(function)) {
                var model = nativeModel(observer, function, placed.point());
                name(model, open, display.creeper);
                display.add(model);
            }
            var creepers = buildCreepers(display, display.creeper);
            displays.put(observer.getUUID(), display);
            addGlowTeam(display, creepers, display.connection::send);
            for (var model : display.models.values()) spawn(model, display.connection::send);
        }
    }

    private static void replaceCreeper(Viewer display, CreeperBooth next) {
        var old = display.models.values().stream().filter(model -> model.function == LobbyFunction.ROLE_CREEPER).toList();
        if (old.isEmpty()) return;
        List<Packet<? super ClientGamePacketListener>> packets = new ArrayList<>();
        packets.add(removal(old));
        for (var model : old) display.models.remove(model.click.getId());
        if (display.goldTeam != null) {
            packets.add(ClientboundSetPlayerTeamPacket.createRemovePacket(display.goldTeam));
            display.goldTeam = null;
        }
        var creepers = buildCreepers(display, next);
        addGlowTeam(display, creepers, packets::add);
        for (var model : creepers) spawn(model, packets::add);
        if (display.creeper.state == CreeperState.LOCKED && next.state == CreeperState.UNLOCKED) {
            for (var point : display.creeperPoints) {
                packets.add(particles(point, true));
                packets.add(new ClientboundSoundPacket(Holder.direct(SoundEvents.VAULT_OPEN_SHUTTER), SoundSource.BLOCKS,
                        point.x(), point.y() + .75, point.z(), 1f, 1f, display.owner.getRandom().nextLong()));
            }
        }
        display.connection.send(new ClientboundBundlePacket(packets));
    }

    private static List<Model> buildCreepers(Viewer display, CreeperBooth booth) {
        var creepers = new ArrayList<Model>();
        for (var point : display.creeperPoints) {
            var model = booth.state == CreeperState.LOCKED ? statue(display.owner, point)
                    : nativeModel(display.owner, LobbyFunction.ROLE_CREEPER, point);
            if (booth.state == CreeperState.UNLOCKED) model.click.setGlowingTag(true);
            name(model, display.open, booth);
            creepers.add(model);
            display.add(model);
        }
        return creepers;
    }

    private static void addGlowTeam(Viewer display, List<Model> creepers,
                                    java.util.function.Consumer<Packet<? super ClientGamePacketListener>> send) {
        if (creepers.isEmpty() || !creepers.getFirst().click.isCurrentlyGlowing()) return;
        var scoreboard = new Scoreboard();
        // All booths for this viewer share a team, with only this connection's fake UUIDs.
        var team = scoreboard.addPlayerTeam(CREEPER_TEAM_PREFIX + creepers.getFirst().click.getId());
        team.setColor(Optional.of(TeamColor.GOLD));
        for (var model : creepers) scoreboard.addPlayerToTeam(model.click.getStringUUID(), team);
        send.accept(ClientboundSetPlayerTeamPacket.createAddOrModifyPacket(team, true));
        display.goldTeam = team;
    }

    private static Model nativeModel(ServerPlayer observer, LobbyFunction function, Point point) {
        // Templates bypass spawn admission: the peaceful lobby never spawns a live mob.
        Mob entity = switch (function) {
            case ROLE_ZOMBIE -> new Zombie(EntityTypes.ZOMBIE, observer.level());
            case ROLE_SKELETON -> new Skeleton(EntityTypes.SKELETON, observer.level());
            case ROLE_CREEPER -> new Creeper(EntityTypes.CREEPER, observer.level());
            case MERCHANT -> new Witch(EntityTypes.WITCH, observer.level());
            default -> throw new IllegalStateException("Not a model function");
        };
        place(entity, point, 0);
        entity.setYHeadRot(point.yaw());
        entity.setNoAi(true); entity.setNoGravity(true); entity.setSilent(true);
        entity.setCustomNameVisible(true);
        if (function == LobbyFunction.ROLE_SKELETON) entity.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
        return new Model(function, entity, entity, List.of(entity));
    }

    private static Model statue(ServerPlayer observer, Point point) {
        var parts = new ArrayList<Entity>();
        var stone = Blocks.POLISHED_ANDESITE.defaultBlockState();
        // CreeperModel's pixel boxes with the feet at Y=0 and the booth's front in local +Z.
        parts.add(stonePart(observer, point, stone, -4, 18, -4, 8, 8, 8));
        parts.add(stonePart(observer, point, stone, -4, 6, -2, 8, 12, 4));
        for (int x : new int[]{-4, 0}) for (int z : new int[]{2, -6}) {
            parts.add(stonePart(observer, point, stone, x, 0, z, 4, 6, 4));
        }
        var chain = Blocks.IRON_CHAIN.defaultBlockState().setValue(ChainBlock.AXIS, Direction.Axis.Y);
        for (int angle : new int[]{-35, 35}) {
            var rotation = new Quaternionf().rotationZ((float) Math.toRadians(angle));
            var scale = new Vector3f(.32f, .88f, .32f);
            var min = new Vector3f(0, .75f, .16f).sub(rotation.transform(new Vector3f(scale).mul(.5f)));
            parts.add(blockDisplay(observer, point, chain, new Transformation(min, rotation, scale, null)));
        }
        var vault = Blocks.VAULT.defaultBlockState().setValue(VaultBlock.FACING, Direction.SOUTH);
        parts.add(blockDisplay(observer, point, vault, new Transformation(
                new Vector3f(-.125f, .625f, .13f), null, new Vector3f(.25f), null)));

        var click = new Interaction(EntityTypes.INTERACTION, observer.level());
        place(click, point, 0);
        var hitbox = (InteractionAccessor) click;
        hitbox.hardrush$setWidth(.75f);
        hitbox.hardrush$setHeight(1.65f);
        parts.add(click);

        var label = new Display.TextDisplay(EntityTypes.TEXT_DISPLAY, observer.level());
        place(label, point, 1.95);
        ((DisplayAccessor) label).hardrush$setBillboardConstraints(Display.BillboardConstraints.CENTER);
        parts.add(label);
        return new Model(LobbyFunction.ROLE_CREEPER, click, label, List.copyOf(parts));
    }

    private static Display.BlockDisplay stonePart(ServerPlayer observer, Point point, BlockState stone,
                                                  int x, int y, int z, int width, int height, int depth) {
        return blockDisplay(observer, point, stone, new Transformation(
                new Vector3f(x, y, z).div(16), null, new Vector3f(width, height, depth).div(16), null));
    }

    private static Display.BlockDisplay blockDisplay(ServerPlayer observer, Point point, BlockState state, Transformation transform) {
        var block = new Display.BlockDisplay(EntityTypes.BLOCK_DISPLAY, observer.level());
        place(block, point, 0);
        ((BlockDisplayAccessor) block).hardrush$setBlockState(state);
        ((DisplayAccessor) block).hardrush$setTransformation(transform);
        return block;
    }

    private static void place(Entity entity, Point point, double height) {
        if (entity.getId() <= lastNativeId) throw new IllegalStateException("Native entity IDs exhausted; restart required");
        lastNativeId = entity.getId();
        entity.setPos(point.x(), point.y() + height, point.z());
        entity.setYRot(point.yaw());
    }

    private static ClientboundLevelParticlesPacket particles(Point point, boolean burst) {
        return new ClientboundLevelParticlesPacket(ParticleTypes.WAX_ON, false, false, point.x(), point.y() + .8, point.z(),
                burst ? .33f : .25f, burst ? .45f : .3f, burst ? .33f : .25f, .02f, burst ? 20 : 3);
    }

    private static void spawn(Model model, java.util.function.Consumer<Packet<? super ClientGamePacketListener>> send) {
        for (var entity : model.entities) {
            send.accept(new ClientboundAddEntityPacket(entity.getId(), entity.getUUID(), entity.getX(), entity.getY(),
                    entity.getZ(), entity.getXRot(), entity.getYRot(), entity.getType(), 0, Vec3.ZERO, entity.getYHeadRot()));
            metadata(entity, send);
        }
        if (model.click instanceof Mob mob && !mob.getMainHandItem().isEmpty()) {
            send.accept(new ClientboundSetEquipmentPacket(mob.getId(),
                    List.of(Pair.of(EquipmentSlot.MAINHAND, mob.getMainHandItem().copy()))));
        }
    }

    private static void metadata(Entity entity, java.util.function.Consumer<Packet<? super ClientGamePacketListener>> send) {
        var values = entity.getEntityData().getNonDefaultValues();
        if (values != null) send.accept(new ClientboundSetEntityDataPacket(entity.getId(), values));
    }

    /** Outside a personal hunt the name tag says when the model works, instead of a sign beside it. */
    private static void name(Model model, boolean open, CreeperBooth booth) {
        String who = switch (model.function) {
            case ROLE_ZOMBIE -> "僵尸"; case ROLE_SKELETON -> "骷髅"; case ROLE_CREEPER -> "苦力怕";
            case MERCHANT -> "女巫商人";
            default -> throw new IllegalStateException("Not a model function");
        };
        String what = "右键选择角色";
        if (!open) what = "极限挑战时开放";
        else if (model.function == LobbyFunction.MERCHANT) what = "右键交易";
        else if (model.function == LobbyFunction.ROLE_CREEPER) what = switch (booth.state) {
            case NATIVE -> what;
            case LOCKED -> booth.price + " 怪物币解锁";
            case UNLOCKED -> "已解锁 · 右键选择角色";
        };
        var text = Component.literal(who + " · " + what);
        if (model.nameTag instanceof Display.TextDisplay label) ((TextDisplayAccessor) label).hardrush$setText(text);
        else model.nameTag.setCustomName(text);
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
        var result = router.validate(observer, display.round, LobbyContext.RULES, model.click.position().add(0, .5, 0));
        var hit = packet.location();
        if (result == LobbyInteractions.Result.ACCEPTED && (packet.hand() != InteractionHand.MAIN_HAND
                || hit != null && (!Double.isFinite(hit.lengthSqr()) || hit.lengthSqr() > 9)))
            result = LobbyInteractions.Result.INPUT;
        if (result == LobbyInteractions.Result.ACCEPTED) LobbyRuntime.activate(observer, model.function, null);
        return result;
    }

    private static ClientboundRemoveEntitiesPacket removal(Collection<Model> models) {
        return new ClientboundRemoveEntitiesPacket(models.stream().flatMap(model -> model.entities.stream()).mapToInt(Entity::getId).toArray());
    }

    void remove(ServerPlayer observer) {
        router.requireThread();
        var display = displays.get(observer.getUUID());
        if (display == null || display.owner != observer) return;
        displays.remove(observer.getUUID());
        if (!display.connection.isAcceptingMessages()) return;
        // Entities disappear on dimension changes; teams live on the original connection's scoreboard.
        if (display.goldTeam != null) display.connection.send(ClientboundSetPlayerTeamPacket.createRemovePacket(display.goldTeam));
        if (display.connection == observer.connection && LobbyWorld.isLobby(observer.level()))
            display.connection.send(removal(display.models.values()));
    }

    void clear() { router.requireThread(); for (var display : List.copyOf(displays.values())) remove(display.owner); }
}
