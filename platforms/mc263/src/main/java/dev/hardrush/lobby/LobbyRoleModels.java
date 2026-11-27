package dev.hardrush.lobby;

import com.mojang.datafixers.util.Pair;
import com.mojang.math.Transformation;
import dev.hardrush.core.lobby.LobbyContext;
import dev.hardrush.core.lobby.plan.LobbyFunction;
import dev.hardrush.core.lobby.plan.Point;
import dev.hardrush.monster.mixin.CreeperPoweredAccessor;
import dev.hardrush.world.LobbyWorld;
import dev.hardrush.world.mixin.BlockDisplayAccessor;
import dev.hardrush.world.mixin.DisplayAccessor;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import java.util.stream.Stream;
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
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Witch;
import net.minecraft.world.entity.monster.skeleton.Skeleton;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChainBlock;
import net.minecraft.world.level.block.IronBarsBlock;
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
    /** A free creeper, a caged one, one let out for a price, and one a second payment called lightning down on. */
    enum CreeperState {
        NATIVE, LOCKED, UNLOCKED, CHARGED;
        boolean caged() { return this == LOCKED; }
        boolean paid() { return this == UNLOCKED || this == CHARGED; }
    }
    /** {@code price}: what the next right-click costs, or 0 when it only selects the role. */
    record CreeperBooth(CreeperState state, int price) {
        static final CreeperBooth NATIVE = new CreeperBooth(CreeperState.NATIVE, 0);
    }

    private record Model(LobbyFunction function, Point point, Mob mob) {}
    /** One piece of a locked booth's cage, and where it ends up once the cage falls open. */
    private record Bar(Display.BlockDisplay display, Transformation fallen) {}
    private static final class Viewer {
        final ServerPlayer owner;
        final ServerGamePacketListenerImpl connection;
        final UUID round;
        final List<Point> creeperPoints;
        final Map<Integer, Model> models = new LinkedHashMap<>();
        final List<Bar> cage = new ArrayList<>();
        /** Cage pieces still playing their fall after an unlock, removed at {@link #fallenAt}. */
        List<Entity> fallen = List.of();
        int fallenAt;
        /** Server tick at which a refused creeper stops swelling, or -1. */
        int hissUntil = -1;
        /** Server tick until which booth clicks are dropped, so the held click that unlocked does not also pay for the charge. */
        int deafUntil;
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
        void add(Model model) { models.put(model.mob.getId(), model); }
        List<Model> creepers() {
            return models.values().stream().filter(model -> model.function == LobbyFunction.ROLE_CREEPER).toList();
        }
        IntStream ids() {
            return IntStream.concat(models.keySet().stream().mapToInt(Integer::intValue),
                    Stream.concat(cage.stream().map(Bar::display), fallen.stream()).mapToInt(Entity::getId));
        }
    }

    private static final List<LobbyFunction> NATIVE_MODELS = List.of(LobbyFunction.ROLE_ZOMBIE, LobbyFunction.ROLE_SKELETON,
            LobbyFunction.MERCHANT);
    private static final String CREEPER_TEAM_PREFIX = "hardrush_creeper_booth_";
    private static final int BUNDLE_LIMIT = 4000;
    /**
     * The cage stays inside the booth's own 1x1x2 column, the only space a lobby pack promises around a model point;
     * it hangs from nothing and rests on nothing, so it fits wherever a pack author moves the booth.
     */
    private static final float CAGE_HALF = .45f, CAGE_TIER = .9f;
    private static final int FALL_TICKS = 8;
    /** Well short of the 30 ticks a creeper swells before it would burst. */
    private static final int HISS_TICKS = 14;
    /** A native held right-click repeats every 4 ticks. */
    private static final int DEAF_TICKS = 20;
    private static final double WATCH_RANGE = 8, CREEPER_EYE = 1.445;
    private static final float WATCH_YAW = 60, WATCH_PITCH = 30;
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

    /** The cage pieces a locked creeper booth currently shows this viewer; empty in any other state. */
    public List<Integer> cage(ServerPlayer observer) {
        router.requireThread();
        var display = displays.get(observer.getUUID());
        if (display == null || display.owner != observer) return List.of();
        return display.cage.stream().map(bar -> bar.display.getId()).toList();
    }

    void tick(MinecraftServer server) {
        router.requireThread();
        for (var display : List.copyOf(displays.values())) {
            if (!available(display.owner, display.round) || display.connection != display.owner.connection)
                remove(display.owner);
        }
        var round = router.round();
        boolean open = LobbyRuntime.courtyardOpen(server);
        int now = server.getTickCount();
        boolean sparkle = now % 40 == 0;
        for (var display : displays.values()) {
            Consumer<Packet<? super ClientGamePacketListener>> send = display.connection::send;
            var creeper = LobbyRuntime.creeperBooth(display.owner);
            if (display.open != open || !display.creeper.equals(creeper)) {
                for (var model : display.models.values()) {
                    if (display.open == open && model.function != LobbyFunction.ROLE_CREEPER) continue;
                    name(model, open, creeper);
                    metadata(model.mob, send);
                }
            }
            display.open = open;
            if (!display.fallen.isEmpty() && now >= display.fallenAt) {
                send.accept(new ClientboundRemoveEntitiesPacket(display.fallen.stream().mapToInt(Entity::getId).toArray()));
                display.fallen = List.of();
            }
            if (display.hissUntil >= 0 && now >= display.hissUntil) calm(display, send);
            if (display.creeper.state != creeper.state) restate(display, creeper, now);
            else if (creeper.state == CreeperState.LOCKED) watch(display, send);
            else if (sparkle && creeper.state.paid()) {
                for (var point : display.creeperPoints) send.accept(particles(point, creeper.state, false));
            }
            display.creeper = creeper;
        }
        for (var observer : server.getPlayerList().getPlayers()) {
            if (!available(observer, round) || displays.containsKey(observer.getUUID())) continue;
            var plan = LobbyWorld.open(server).plan();
            var points = plan.models(LobbyFunction.ROLE_CREEPER).stream().map(placed -> placed.point()).toList();
            var display = new Viewer(observer, round, points, LobbyRuntime.creeperBooth(observer), open);
            for (var function : NATIVE_MODELS) for (var placed : plan.models(function)) display.add(nativeModel(observer, function, placed.point()));
            for (var point : points) display.add(nativeModel(observer, LobbyFunction.ROLE_CREEPER, point));
            displays.put(observer.getUUID(), display);
            for (var model : display.models.values()) {
                name(model, open, display.creeper);
                spawn(model.mob, display.connection::send);
            }
            dress(display, CreeperState.NATIVE, display.connection::send);
        }
    }

    /** The booth keeps its creeper through every state; only the cage, the gold glow and the charge around it change. */
    private static void restate(Viewer display, CreeperBooth next, int now) {
        List<Packet<? super ClientGamePacketListener>> packets = new ArrayList<>();
        var before = display.creeper.state;
        if (before.caged() && !next.state.caged()) {
            calm(display, packets::add);
            for (var model : display.creepers()) turn(model.mob, model.point.yaw(), 0, packets::add);
            if (next.state.paid()) {
                fall(display, now, packets);
                display.deafUntil = now + DEAF_TICKS;
            } else packets.add(new ClientboundRemoveEntitiesPacket(display.cage.stream().mapToInt(bar -> bar.display.getId()).toArray()));
            display.cage.clear();
        }
        if (before.paid() && !next.state.paid() && display.goldTeam != null) {
            packets.add(ClientboundSetPlayerTeamPacket.createRemovePacket(display.goldTeam));
            display.goldTeam = null;
        }
        // Lightning strikes a creeper already let out; one found charged on arrival, or re-caged, just changes.
        if (before == CreeperState.UNLOCKED && next.state == CreeperState.CHARGED) {
            for (var point : display.creeperPoints) packets.add(strike(display.owner, point));
        }
        display.creeper = next;
        dress(display, before, packets::add);
        // A native client rejects a bundle above 4096 packets; a lobby with many booths spans several, still in order.
        for (int from = 0; from < packets.size(); from += BUNDLE_LIMIT)
            display.connection.send(new ClientboundBundlePacket(packets.subList(from, Math.min(packets.size(), from + BUNDLE_LIMIT))));
    }

    /** Brings the already spawned creepers and what surrounds them from {@code before} to the current booth state. */
    private static void dress(Viewer display, CreeperState before, Consumer<Packet<? super ClientGamePacketListener>> send) {
        var creepers = display.creepers();
        if (creepers.isEmpty()) return;
        var state = display.creeper.state;
        if (state.caged() && !before.caged()) {
            for (var point : display.creeperPoints) display.cage.addAll(cage(display.owner, point));
            for (var bar : display.cage) spawn(bar.display, send);
        }
        for (var model : creepers) {
            model.mob.setGlowingTag(state.paid());
            model.mob.getEntityData().set(CreeperPoweredAccessor.hardrush$poweredData(), state == CreeperState.CHARGED);
            changed(model.mob, send);
        }
        if (state.paid() && display.goldTeam == null) {
            var scoreboard = new Scoreboard();
            // All booths for this viewer share a team, with only this connection's fake UUIDs.
            var team = scoreboard.addPlayerTeam(CREEPER_TEAM_PREFIX + creepers.getFirst().mob.getId());
            team.setColor(Optional.of(TeamColor.GOLD));
            for (var model : creepers) scoreboard.addPlayerToTeam(model.mob.getStringUUID(), team);
            send.accept(ClientboundSetPlayerTeamPacket.createAddOrModifyPacket(team, true));
            display.goldTeam = team;
        }
    }

    /**
     * A lightning bolt only this viewer is sent. The client draws it, plays its thunder and discards it on its own
     * ({@code LightningBolt.tick}); fire and damage are the server's side of a bolt, and this one never exists there.
     */
    private static Packet<? super ClientGamePacketListener> strike(ServerPlayer observer, Point point) {
        var bolt = new LightningBolt(EntityTypes.LIGHTNING_BOLT, observer.level());
        place(bolt, point, 0);
        return new ClientboundAddEntityPacket(bolt.getId(), bolt.getUUID(), bolt.getX(), bolt.getY(), bolt.getZ(),
                0, 0, bolt.getType(), 0, Vec3.ZERO, 0);
    }

    /** The paid-for cage drops flat onto the booth floor, then {@link #tick} removes the pieces. */
    private static void fall(Viewer display, int now, List<Packet<? super ClientGamePacketListener>> packets) {
        for (var bar : display.cage) {
            var accessor = (DisplayAccessor) bar.display;
            accessor.hardrush$setTransformationInterpolationDuration(FALL_TICKS);
            // The client only starts a new interpolation when this value arrives with the transformation.
            accessor.hardrush$setTransformationInterpolationDelay(0);
            accessor.hardrush$setTransformation(bar.fallen);
            changed(bar.display, packets::add);
        }
        if (!display.fallen.isEmpty())
            packets.add(new ClientboundRemoveEntitiesPacket(display.fallen.stream().mapToInt(Entity::getId).toArray()));
        display.fallen = display.cage.stream().<Entity>map(Bar::display).toList();
        display.fallenAt = now + FALL_TICKS + 2;
        for (var point : display.creeperPoints) {
            packets.add(particles(point, CreeperState.UNLOCKED, true));
            packets.add(new ClientboundSoundPacket(Holder.direct(SoundEvents.VAULT_OPEN_SHUTTER), SoundSource.BLOCKS,
                    point.x(), point.y() + .75, point.z(), 1f, 1f, display.owner.getRandom().nextLong()));
        }
    }

    /** A caged creeper follows its viewer with its head, as far as it can without turning its body from the booth's front. */
    private static void watch(Viewer display, Consumer<Packet<? super ClientGamePacketListener>> send) {
        var eye = display.owner.getEyePosition();
        for (var model : display.creepers()) {
            var point = model.point;
            double dx = eye.x - point.x(), dy = eye.y - point.y() - CREEPER_EYE, dz = eye.z - point.z();
            double flat = Math.sqrt(dx * dx + dz * dz);
            float yaw = point.yaw(), pitch = 0;
            if (flat > .3 && flat * flat + dy * dy <= WATCH_RANGE * WATCH_RANGE) {
                float toward = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90;
                yaw += Mth.clamp(Mth.wrapDegrees(toward - yaw), -WATCH_YAW, WATCH_YAW);
                pitch = Mth.clamp((float) -Math.toDegrees(Math.atan2(dy, flat)), -WATCH_PITCH, WATCH_PITCH);
            }
            turn(model.mob, yaw, pitch, send);
        }
    }

    private static void turn(Mob mob, float yaw, float pitch, Consumer<Packet<? super ClientGamePacketListener>> send) {
        byte head = Mth.packDegrees(yaw), nod = Mth.packDegrees(pitch);
        if (head != Mth.packDegrees(mob.getYHeadRot())) {
            mob.setYHeadRot(yaw);
            send.accept(new ClientboundRotateHeadPacket(mob, head));
        }
        if (nod != Mth.packDegrees(mob.getXRot())) {
            mob.setXRot(pitch);
            send.accept(new ClientboundMoveEntityPacket.Rot(mob.getId(), Mth.packDegrees(mob.getYRot()), nod, true));
        }
    }

    /**
     * The nearest creeper with a price on it answers a click it cannot honour by swelling and hissing for a moment. Only the
     * client animates it: the template is never ticked, and a client creeper never explodes on its own.
     */
    void hiss(ServerPlayer observer) {
        router.requireThread();
        var display = displays.get(observer.getUUID());
        if (display == null || display.owner != observer || display.creeper.price <= 0) return;
        var nearest = display.creepers().stream()
                .min(Comparator.comparingDouble(model -> model.mob.distanceToSqr(observer))).orElse(null);
        if (nearest == null) return;
        ((Creeper) nearest.mob).setSwellDir(1);
        changed(nearest.mob, display.connection::send);
        var point = nearest.point;
        display.connection.send(new ClientboundSoundPacket(Holder.direct(SoundEvents.CREEPER_PRIMED), SoundSource.HOSTILE,
                point.x(), point.y() + 1, point.z(), 1f, .5f, observer.getRandom().nextLong()));
        display.hissUntil = observer.level().getServer().getTickCount() + HISS_TICKS;
    }

    private static void calm(Viewer display, Consumer<Packet<? super ClientGamePacketListener>> send) {
        if (display.hissUntil < 0) return;
        display.hissUntil = -1;
        for (var model : display.creepers()) {
            ((Creeper) model.mob).setSwellDir(-1);
            changed(model.mob, send);
        }
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
        return new Model(function, point, entity);
    }

    /** Iron bars on four sides in two tiers, an iron trapdoor for a roof, and a chained vault on the front as the lock. */
    private static List<Bar> cage(ServerPlayer observer, Point point) {
        var parts = new ArrayList<Bar>();
        // Bars joined along one axis are a flat sheet through the middle of their block; the booth's front is local +Z.
        var across = Blocks.IRON_BARS.defaultBlockState().setValue(IronBarsBlock.EAST, true).setValue(IronBarsBlock.WEST, true);
        var along = Blocks.IRON_BARS.defaultBlockState().setValue(IronBarsBlock.NORTH, true).setValue(IronBarsBlock.SOUTH, true);
        float width = CAGE_HALF * 2;
        for (int tier = 0; tier < 2; tier++) for (float side : new float[]{-CAGE_HALF, CAGE_HALF}) {
            parts.add(bar(observer, point, across, new Transformation(
                    new Vector3f(-CAGE_HALF, tier * CAGE_TIER, side - .5f), null, new Vector3f(width, CAGE_TIER, 1), null)));
            parts.add(bar(observer, point, along, new Transformation(
                    new Vector3f(side - .5f, tier * CAGE_TIER, -CAGE_HALF), null, new Vector3f(1, CAGE_TIER, width), null)));
        }
        parts.add(bar(observer, point, Blocks.IRON_TRAPDOOR.defaultBlockState(), new Transformation(
                new Vector3f(-.48f, 2 * CAGE_TIER, -.48f), null, new Vector3f(.96f, 1, .96f), null)));
        var chain = Blocks.IRON_CHAIN.defaultBlockState().setValue(ChainBlock.AXIS, Direction.Axis.Y);
        for (int angle : new int[]{-35, 35}) {
            var rotation = new Quaternionf().rotationZ((float) Math.toRadians(angle));
            var scale = new Vector3f(.32f, .88f, .32f);
            var min = new Vector3f(0, .825f, CAGE_HALF + .02f).sub(rotation.transform(new Vector3f(scale).mul(.5f)));
            parts.add(bar(observer, point, chain, new Transformation(min, rotation, scale, null)));
        }
        var vault = Blocks.VAULT.defaultBlockState().setValue(VaultBlock.FACING, Direction.SOUTH);
        parts.add(bar(observer, point, vault, new Transformation(
                new Vector3f(-.125f, .7f, CAGE_HALF - .09f), null, new Vector3f(.25f), null)));
        return List.copyOf(parts);
    }

    private static Bar bar(ServerPlayer observer, Point point, BlockState state, Transformation shut) {
        var translation = shut.translation();
        var scale = shut.scale();
        return new Bar(blockDisplay(observer, point, state, shut), new Transformation(
                new Vector3f(translation.x(), 0, translation.z()), shut.leftRotation(),
                new Vector3f(scale.x(), scale.y() * .02f, scale.z()), shut.rightRotation()));
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

    private static ClientboundLevelParticlesPacket particles(Point point, CreeperState state, boolean burst) {
        var kind = state == CreeperState.CHARGED ? ParticleTypes.ELECTRIC_SPARK : ParticleTypes.WAX_ON;
        return new ClientboundLevelParticlesPacket(kind, false, false, point.x(), point.y() + .8, point.z(),
                burst ? .33f : .25f, burst ? .45f : .3f, burst ? .33f : .25f, .02f, burst ? 20 : 3);
    }

    private static void spawn(Entity entity, Consumer<Packet<? super ClientGamePacketListener>> send) {
        send.accept(new ClientboundAddEntityPacket(entity.getId(), entity.getUUID(), entity.getX(), entity.getY(),
                entity.getZ(), entity.getXRot(), entity.getYRot(), entity.getType(), 0, Vec3.ZERO, entity.getYHeadRot()));
        metadata(entity, send);
        if (entity instanceof Mob mob && !mob.getMainHandItem().isEmpty()) {
            send.accept(new ClientboundSetEquipmentPacket(mob.getId(),
                    List.of(Pair.of(EquipmentSlot.MAINHAND, mob.getMainHandItem().copy()))));
        }
    }

    private static void metadata(Entity entity, Consumer<Packet<? super ClientGamePacketListener>> send) {
        var values = entity.getEntityData().getNonDefaultValues();
        if (values != null) send.accept(new ClientboundSetEntityDataPacket(entity.getId(), values));
    }

    /** Sends what changed since the last call, including values that went back to their default. */
    private static void changed(Entity entity, Consumer<Packet<? super ClientGamePacketListener>> send) {
        var values = entity.getEntityData().packDirty();
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
            case UNLOCKED -> booth.price > 0 ? "已解锁 · 再付 " + booth.price + " 怪物币引雷" : "已解锁 · 右键选择角色";
            case CHARGED -> "已带电 · 爆炸威力翻倍";
        };
        model.mob.setCustomName(Component.literal(who + " · " + what));
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
        var result = router.validate(observer, display.round, LobbyContext.RULES, model.mob.position().add(0, .5, 0));
        var hit = packet.location();
        if (result == LobbyInteractions.Result.ACCEPTED && (packet.hand() != InteractionHand.MAIN_HAND
                || hit != null && (!Double.isFinite(hit.lengthSqr()) || hit.lengthSqr() > 9)))
            result = LobbyInteractions.Result.INPUT;
        if (result == LobbyInteractions.Result.ACCEPTED && model.function == LobbyFunction.ROLE_CREEPER
                && observer.level().getServer().getTickCount() < display.deafUntil) return result;
        if (result == LobbyInteractions.Result.ACCEPTED) LobbyRuntime.activate(observer, model.function, null, true);
        return result;
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
            display.connection.send(new ClientboundRemoveEntitiesPacket(display.ids().toArray()));
    }

    void clear() { router.requireThread(); for (var display : List.copyOf(displays.values())) remove(display.owner); }
}
