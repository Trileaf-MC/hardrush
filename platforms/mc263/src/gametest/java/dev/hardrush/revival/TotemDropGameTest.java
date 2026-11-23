package dev.hardrush.revival;

import com.mojang.authlib.GameProfile;
import dev.hardrush.HardRushProperties;
import dev.hardrush.core.api.DamagePort.AttackKind;
import dev.hardrush.core.combat.CombatActor;
import dev.hardrush.core.combat.CombatOrigin;
import dev.hardrush.core.config.GameMode;
import dev.hardrush.core.config.RulesConfig;
import dev.hardrush.core.round.*;
import dev.hardrush.gametest.TestClients;
import dev.hardrush.gametest.TestTeardown;
import dev.hardrush.notice.Notices;
import dev.hardrush.revival.TotemProjection.Decision;
import dev.hardrush.revival.TotemProjection.Identity;
import dev.hardrush.revival.TotemProjection.State;
import dev.hardrush.round.RoundSession;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.vehicle.minecart.MinecartHopper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.TeamColor;

/**
 * Revival totems dropped by a human's death or forced out of a human's hand (docs/plan/复生图腾掉落.md, 1.1 to 1.3, 1.5,
 * 1.8, 2.3, 2.4) on real fixture players outside the competition runtime: the round runtime is scripted through {@link TotemDrops.Access}, its commits replayed
 * through {@link TotemDrops#onCommitted} as {@code RoundRuntime} does. One sequence, because {@link TotemDrops} is
 * configured per server and its patrol sweeps every loaded world. {@code -PhardrushTotemDrops=true} runs it alone.
 */
public final class TotemDropGameTest {
    private static final AtomicInteger ARENAS = new AtomicInteger();
    /** {@code ItemEntity.INFINITE_LIFETIME}, the age {@code setUnlimitedLifetime} fixes. */
    private static final int UNLIMITED_LIFETIME = -32768;

    @GameTest(maxTicks = 1200)
    public void droppedTotemsSpawnRegisterAnnounceAndAreTakenOnlyThroughTheRegistry(GameTestHelper test) {
        if (HardRushProperties.competitionEnabled()) { test.succeed(); return; }
        var f = new Fixture(test);
        TestTeardown.atEnd(test, f::close);
        var s = new Script(f.server);
        var killer = f.client("K"); var victim = f.client("V"); var second = f.client("X");
        var human = f.client("H"); var camp = f.client("M"); var deployed = f.client("D");
        var withdrawn = f.client("W"); var outsider = f.client("N");
        UUID offline = UUID.randomUUID();
        for (var c : List.of(killer, victim, second, human)) s.put(c.id(), c.name(), ParticipantRole.HUMAN_ACTIVE, 1, 0, 0, true);
        s.put(camp.id(), camp.name(), ParticipantRole.MONSTER_LOBBY, 1, 0, 0, true);
        s.put(deployed.id(), deployed.name(), ParticipantRole.MONSTER_DEPLOYED, 1, 0, 1, true);
        s.put(withdrawn.id(), withdrawn.name(), ParticipantRole.WITHDRAWN, 1, 0, 0, true);
        s.put(offline, "Offline", ParticipantRole.HUMAN_ACTIVE, 1, 0, 0, false);
        s.collectors.addAll(List.of(killer.id(), human.id()));
        TotemDrops.configure(f.server, s);
        TestTeardown.atEnd(test, () -> TotemDrops.unconfigure(f.server));
        victim.move(f.base.offset(0, 0, 4)); second.move(f.base.offset(3, 0, 4));
        var refs = new Object() { ItemEntity vetoed, first, other, reload, traveller; UUID firstToken, otherToken, reloadToken; };

        test.startSequence().thenWaitUntil(f::awaitArena).thenExecute(() -> {
            TotemDrops.tick(f.server);
            for (var c : f.clients) test.assertTrue(c.team(p -> p.getTeamAction() == ClientboundSetPlayerTeamPacket.Action.ADD
                    && p.getParameters().flatMap(ClientboundSetPlayerTeamPacket.Parameters::color).equals(Optional.of(TeamColor.GOLD))),
                    "Everyone online receives the packet-only gold team: " + c.name());
            test.assertTrue(f.server.getScoreboard().getPlayerTeam(TotemDrops.TEAM) == null, "The server scoreboard is never written");

            // Deaths that drop nothing in the world: a monster's kill (it is paid), normal mode.
            var round = s.snapshot();
            var monsterKill = Optional.of(new CombatOrigin(s.roundId, UUID.randomUUID(),
                    new CombatActor(deployed.id(), ParticipantRole.MONSTER_DEPLOYED, 1, 1), AttackKind.CREEPER, Optional.empty(), List.of()));
            test.assertTrue(TotemDrops.prepare(victim.player, round, monsterKill).isEmpty(), "A monster's kill drops nothing");
            s.rules = RulesConfig.defaults().withMode(GameMode.NORMAL);
            test.assertTrue(TotemDrops.prepare(victim.player, s.snapshot(), s.melee(killer.id())).isEmpty(), "Normal mode drops nothing");
            s.rules = RulesConfig.defaults();
            // A suicide and a death without credit release an unclaimed totem (守恒 1.1).
            for (var credit : List.of(s.melee(victim.id()), Optional.<CombatOrigin>empty())) {
                var token = TotemDrops.prepare(victim.player, s.snapshot(), credit);
                test.assertTrue(token.isPresent(), "Every ended human life releases one: " + credit);
                TotemDrops.captured(victim.player, s.roundId, token.get(), false);
            }
            // Below the world: where the fall began, on the nearest standing ground; with no recorded fall, the arrival point.
            victim.move(new Vec3(f.base.getX() + .5, f.level.getMinY() - 10, f.base.getZ() + 4.5));
            fallStart(victim.player, null);
            var voidToken = TotemDrops.prepare(victim.player, s.snapshot(), s.melee(killer.id()));
            test.assertTrue(voidToken.isPresent(), "A death in the void drops too");
            TotemDrops.captured(victim.player, s.roundId, voidToken.get(), false);
            var arrival = TotemDrops.place(victim.player);
            test.assertTrue(arrival.level() == f.level.getServer().getLevel(f.level.getRespawnData().dimension())
                    && arrival.at().equals(Vec3.atBottomCenterOf(f.level.getRespawnData().pos())), "No recorded fall: the arrival point");
            fallStart(victim.player, new Vec3(f.base.getX() + 13.2, f.base.getY() - .2, f.base.getZ() + .5));
            var edge = TotemDrops.place(victim.player);
            test.assertTrue(edge.level() == f.level && edge.at().y == f.base.getY()
                    && edge.at().distanceTo(new Vec3(f.base.getX() + 12.5, f.base.getY(), f.base.getZ() + .5)) < 1.5,
                    "Knocked off the edge: back on the edge it fell from, " + edge.at());
            fallStart(victim.player, null);
            victim.move(f.base.offset(0, 0, 4));

            // A death the session did not capture drops nothing either.
            var refused = TotemDrops.prepare(victim.player, s.snapshot(), s.melee(killer.id())).orElseThrow();
            TotemDrops.captured(victim.player, s.roundId, refused, false);
            test.assertTrue(f.drops(refused).isEmpty(), "An uncaptured death spawns no totem");

            // A captured death drops one at the body, pending until its batch commits.
            var vetoed = TotemDrops.prepare(victim.player, s.snapshot(), s.melee(killer.id())).orElseThrow();
            TotemDrops.captured(victim.player, s.roundId, vetoed, true);
            refs.vetoed = only(test, f.drops(vetoed), "The captured death drops exactly one totem");
            var item = refs.vetoed;
            test.assertTrue(item.position().distanceTo(victim.player.position()) < 2, "It lies where the victim fell");
            var stack = item.getItem();
            test.assertTrue(RevivalTotems.is(stack) && stack.is(Items.TOTEM_OF_UNDYING) && stack.getCount() == 1, "One revival totem");
            test.assertValueEqual(stack.getHoverName().getString(), "复生图腾", "The held item's gold name");
            test.assertFalse(stack.has(DataComponents.DEATH_PROTECTION), "Never a native death guard");
            test.assertTrue(Boolean.TRUE.equals(stack.get(DataComponents.ENCHANTMENT_GLINT_OVERRIDE)), "The held item's glint");
            test.assertValueEqual(stack.get(DataComponents.LORE).lines().stream().map(Component::getString).toList(), RevivalTotems.DROP_USAGE,
                    "Its own description");
            test.assertTrue(RevivalTotems.identity(stack).isEmpty() && RevivalTotems.dropToken(stack).equals(Optional.of(vetoed)),
                    "Known by its token, never mistaken for a projected copy");
            test.assertTrue(stack.get(DataComponents.DAMAGE_RESISTANT).isResistantTo(f.level.damageSources().lava()), "Fireproof");
            test.assertTrue(item.isInvulnerable() && item.hasPickUpDelay() && item.hasGlowingTag() && item.isCustomNameVisible()
                    && "复生图腾".equals(item.getCustomName().getString()), "Invulnerable, never natively picked up, glowing, named");
            test.assertValueEqual(item.getAge(), UNLIMITED_LIFETIME, "It never despawns on its own");
            test.assertTrue(human.team(p -> p.getPlayerAction() == ClientboundSetPlayerTeamPacket.Action.ADD
                    && p.getPlayers().contains(item.getStringUUID())), "Its UUID joins the gold team for everyone");

            // Pending: touching does nothing and the patrol leaves it alone.
            item.playerTouch(human.player);
            test.assertValueEqual(s.requests, 0, "A pending totem cannot be taken");
            TotemDrops.patrol(f.server);
            test.assertFalse(item.isRemoved(), "The patrol keeps a pending totem");

            // Its batch commits without registering it (vetoed on commit): removed, and nobody is told.
            s.commit(() -> s.put(victim.id(), victim.name(), ParticipantRole.MONSTER_LOBBY, 1, 0, 0, true));
            test.assertTrue(item.isRemoved(), "An unregistered drop is removed when its death commits");
            for (var c : f.clients) test.assertValueEqual(c.bars.size(), 0, "Nothing is announced for an unregistered drop: " + c.name());

            // Two humans fall in one batch, both registered: everyone else still playing is told once.
            s.put(victim.id(), victim.name(), ParticipantRole.HUMAN_ACTIVE, 2, 0, 0, true);
            var round2 = s.snapshot();
            refs.firstToken = TotemDrops.prepare(victim.player, round2, s.melee(killer.id())).orElseThrow();
            refs.otherToken = TotemDrops.prepare(second.player, round2, s.melee(killer.id())).orElseThrow();
            TotemDrops.captured(victim.player, s.roundId, refs.firstToken, true);
            TotemDrops.captured(second.player, s.roundId, refs.otherToken, true);
            refs.first = only(test, f.drops(refs.firstToken), "First drop");
            refs.other = only(test, f.drops(refs.otherToken), "Second drop");
            s.commit(() -> {
                s.put(victim.id(), victim.name(), ParticipantRole.MONSTER_LOBBY, 2, 0, 0, true);
                s.put(second.id(), second.name(), ParticipantRole.MONSTER_LOBBY, 1, 0, 0, true);
                s.register(refs.firstToken, victim.id(), 2, killer.id());
                s.register(refs.otherToken, second.id(), 1, killer.id());
            });
            test.assertTrue(!refs.first.isRemoved() && !refs.other.isRemoved(), "Registered drops stay");
            String both = victim.name() + " 等 2 人被人类击杀，掉落了复生图腾";
            for (var c : List.of(human, camp, deployed)) test.assertValueEqual(c.bars, List.of(both), "Told once: " + c.name());
            for (var c : List.of(killer, victim, second, withdrawn, outsider))
                test.assertValueEqual(c.bars.size(), 0, "Killer, victims, those out of the round and outsiders are not told: " + c.name());

            // Too young: nobody takes it before two seconds of race time since its registration, however old the entity.
            refs.first.playerTouch(human.player);
            test.assertValueEqual(s.requests, 0, "A fresh drop waits its pickup delay");
            s.challenge += TotemDrops.PICKUP_DELAY_NANOS - 1;
            refs.first.playerTouch(human.player);
            test.assertValueEqual(s.requests, 0, "The delay is race time since registration");
            s.challenge += 1;
        }).thenIdle(1).thenExecute(() -> {
            // A camp monster cannot take it; a human whose inventory is full and holds no totem cannot either.
            refs.first.playerTouch(camp.player);
            test.assertValueEqual(s.requests, 0, "A camp monster cannot take it");
            var inventory = human.player.getInventory();
            for (int i = 0; i < inventory.getNonEquipmentItems().size(); i++) inventory.setItem(i, new ItemStack(Items.COBBLESTONE, 64));
            s.put(human.id(), human.name(), ParticipantRole.HUMAN_ACTIVE, 1, 3, 0, true);
            refs.first.playerTouch(human.player);
            test.assertValueEqual(s.requests, 0, "No room and no stack to join: not taken, like vanilla");
            s.put(human.id(), human.name(), ParticipantRole.HUMAN_ACTIVE, 1, 99, 0, true);
            s.defer = true;
            refs.first.playerTouch(human.player);
            test.assertValueEqual(s.requests, 1, "Beyond a full stack the projection needs no new slot");
            refs.first.playerTouch(killer.player);
            test.assertValueEqual(s.requests, 1, "A claimed totem waits for its answer");
            s.answer(false);
            test.assertFalse(refs.first.isRemoved(), "A refused pickup leaves the totem");
            s.defer = false;
            for (int i = 0; i < inventory.getNonEquipmentItems().size(); i++) inventory.setItem(i, ItemStack.EMPTY);
            s.put(human.id(), human.name(), ParticipantRole.HUMAN_ACTIVE, 1, 0, 0, true);

            // A human takes it: one more charge, the totem in the inventory, the entity gone, the first-pickup line.
            refs.first.playerTouch(human.player);
            test.assertValueEqual(s.requests, 2, "The human asks to take it");
            test.assertTrue(refs.first.isRemoved() && f.drops(refs.firstToken).isEmpty(), "The entity goes once committed");
            test.assertValueEqual(s.players.get(human.id()).revivalCharges(), 1, "One more charge");
            test.assertValueEqual(total(human.player), 1, "The inventory holds it");
            test.assertValueEqual(human.bars.getLast(), Notices.format(TotemDrops.PICKUP_TIP).getString(), "The first pickup explains it");
            int told = human.bars.size();
            refs.first.playerTouch(human.player);
            test.assertValueEqual(s.requests, 2, "A taken token is never asked for again");

            // A monster out hunting takes the other one: paid like a kill, no room needed, told by the round runtime.
            refs.other.playerTouch(deployed.player);
            test.assertValueEqual(s.requests, 3, "A hunting monster asks to take it");
            test.assertTrue(refs.other.isRemoved() && f.drops(refs.otherToken).isEmpty(), "Taken by the monster");
            test.assertValueEqual(s.players.get(deployed.id()).revivalCharges(), 1, "The monster gains one charge");
            test.assertValueEqual(deployed.bars.size(), 1, "No human pickup line for a monster (only the earlier announcement)");
            refs.other = f.registered(s, Vec3.atBottomCenterOf(f.base.offset(5, 0, 5)));
            f.registered.removeLast();

            // Hoppers, hopper minecarts, zombies and allays leave it; a lava pool and an explosion do not destroy it.
            var hopperAt = f.base.offset(8, 0, 8);
            f.level.setBlockAndUpdate(hopperAt, Blocks.HOPPER.defaultBlockState());
            f.registered(s, Vec3.atCenterOf(hopperAt).add(0, .7, 0));
            var cart = EntityTypes.HOPPER_MINECART.create(f.level, EntitySpawnReason.COMMAND);
            cart.setPos(f.base.getX() - 7.5, f.base.getY(), f.base.getZ() + 8.5);
            f.level.addFreshEntity(cart);
            f.registered(s, new Vec3(f.base.getX() - 7.5, f.base.getY() + .2, f.base.getZ() + 8.5));
            var zombie = f.mob(EntityTypes.ZOMBIE.create(f.level, EntitySpawnReason.COMMAND), f.base.offset(-8, 0, 0));
            f.registered(s, Vec3.atBottomCenterOf(f.base.offset(-8, 0, 0)));
            var control = f.mob(EntityTypes.ZOMBIE.create(f.level, EntitySpawnReason.COMMAND), f.base.offset(-8, 0, -4));
            var plain = new ItemEntity(f.level, f.base.getX() - 7.5, f.base.getY(), f.base.getZ() - 3.5, new ItemStack(Items.TOTEM_OF_UNDYING));
            plain.setNoPickUpDelay();
            f.level.addFreshEntity(plain);
            var allay = f.mob(EntityTypes.ALLAY.create(f.level, EntitySpawnReason.COMMAND), f.base.offset(-4, 0, -6));
            allay.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(Items.TOTEM_OF_UNDYING));
            f.registered(s, Vec3.atBottomCenterOf(f.base.offset(-4, 0, -6)));
            var lava = f.base.offset(8, -1, -8);
            f.level.setBlockAndUpdate(lava.below(), Blocks.STONE.defaultBlockState());
            f.level.setBlockAndUpdate(lava, Blocks.LAVA.defaultBlockState());
            f.registered(s, Vec3.atCenterOf(lava));
            var blast = f.registered(s, Vec3.atBottomCenterOf(f.base.offset(10, 0, -2)));
            f.level.explode(null, blast.getX(), blast.getY() + .5, blast.getZ(), 3f, Level.ExplosionInteraction.NONE);
            test.assertFalse(blast.isRemoved(), "An explosion does not destroy it");
            f.keep = List.of(zombie, control, allay, cart);
            test.assertValueEqual(told, human.bars.size(), "No further line on the second touch");
        }).thenIdle(60).thenExecute(() -> {
            test.assertTrue(f.registered.stream().noneMatch(ItemEntity::isRemoved), "Lava, hoppers, minecarts, zombies and allays leave every drop");
            var hopper = (HopperBlockEntity) f.level.getBlockEntity(f.base.offset(8, 0, 8));
            test.assertTrue(hopper.isEmpty() && ((MinecartHopper) f.keep.get(3)).isEmpty(), "Neither hopper took it");
            test.assertTrue(((Mob) f.keep.get(0)).getMainHandItem().isEmpty(), "The zombie did not take it");
            test.assertTrue(((Mob) f.keep.get(1)).getMainHandItem().is(Items.TOTEM_OF_UNDYING), "A zombie does pick up an ordinary stack");
            test.assertTrue(((net.minecraft.world.entity.animal.allay.Allay) f.keep.get(2)).getInventory().isEmpty(), "The allay did not take it");
            test.assertTrue(f.registered.get(3).getY() >= f.base.getY() - 1, "In lava it floats instead of burning");

            // Known by token only: a chunk unload replaces the entity object.
            var far = f.base.offset(2000, 0, 0);
            f.level.setChunkForced(far.getX() >> 4, far.getZ() >> 4, true);
            f.level.setBlockAndUpdate(far.below(), Blocks.STONE.defaultBlockState());
            refs.reload = f.registered(s, Vec3.atBottomCenterOf(far));
            refs.reloadToken = RevivalTotems.dropToken(refs.reload.getItem()).orElseThrow();
            refs.traveller = f.registered(s, Vec3.atBottomCenterOf(f.base.offset(-2, 0, 8)));
            f.level.setChunkForced(far.getX() >> 4, far.getZ() >> 4, false);
        }).thenWaitUntil(() -> require(refs.reload.isRemoved(), "the far chunk unloads")).thenExecute(() -> {
            var far = f.base.offset(2000, 0, 0);
            f.level.setChunkForced(far.getX() >> 4, far.getZ() >> 4, true);
        }).thenWaitUntil(() -> require(!f.drops(refs.reloadToken).isEmpty(), "the far chunk loads again")).thenExecute(() -> {
            var reloaded = only(test, f.drops(refs.reloadToken), "Reloaded drop");
            test.assertTrue(reloaded != refs.reload && reloaded.getUUID().equals(refs.reload.getUUID()), "A new object with the same UUID");
            test.assertValueEqual(reloaded.getAge(), UNLIMITED_LIFETIME, "The unlimited lifetime is saved with the entity");
            int asked = s.requests;
            reloaded.playerTouch(human.player);
            test.assertValueEqual(s.requests, asked, "Registered just now in race time: not yet, reload or not");
            s.challenge += TotemDrops.PICKUP_DELAY_NANOS;
            reloaded.playerTouch(human.player);
            test.assertValueEqual(s.requests, asked + 1, "Asked after a reload (age " + reloaded.getAge() + ")");
            test.assertTrue(reloaded.isRemoved() && s.players.get(human.id()).revivalCharges() == 2, "Taken after a reload");
            var far = f.base.offset(2000, 0, 0);
            f.level.setChunkForced(far.getX() >> 4, far.getZ() >> 4, false);

            // Through the nether and back: two new objects, the same token, still takeable.
            var nether = f.server.getLevel(Level.NETHER);
            var there = new BlockPos(f.base.getX() / 8, 100, f.base.getZ() / 8);
            nether.setChunkForced(there.getX() >> 4, there.getZ() >> 4, true);
            nether.setBlockAndUpdate(there.below(), Blocks.OBSIDIAN.defaultBlockState());
            var traveller = refs.traveller;
            var token = RevivalTotems.dropToken(traveller.getItem()).orElseThrow();
            var away = traveller.teleport(new net.minecraft.world.level.portal.TeleportTransition(nether, Vec3.atBottomCenterOf(there), Vec3.ZERO, 0, 0,
                    net.minecraft.world.level.portal.TeleportTransition.DO_NOTHING));
            test.assertTrue(away instanceof ItemEntity && away != traveller && traveller.isRemoved() && away.level() == nether, "Carried to the nether");
            var back = away.teleport(new net.minecraft.world.level.portal.TeleportTransition(f.level, Vec3.atBottomCenterOf(f.base.offset(-2, 0, 8)),
                    Vec3.ZERO, 0, 0, net.minecraft.world.level.portal.TeleportTransition.DO_NOTHING));
            test.assertTrue(back instanceof ItemEntity && back.getUUID().equals(traveller.getUUID()), "Back with the same UUID");
            nether.setChunkForced(there.getX() >> 4, there.getZ() >> 4, false);
            ((ItemEntity) back).playerTouch(human.player);
            test.assertTrue(back.isRemoved() && f.drops(token).isEmpty() && s.players.get(human.id()).revivalCharges() == 3,
                    "Taken after crossing dimensions");

            // A later single registration tells only who has not been told this round.
            var late = f.client("L");
            s.put(late.id(), late.name(), ParticipantRole.HUMAN_ACTIVE, 1, 0, 0, true);
            // Connected but not admitted yet: entering still hears it.
            var entering = f.client("E");
            s.players.put(entering.id(), new Participant(entering.id(), entering.name(), ParticipantRole.HUMAN_ACTIVE, false, false, 0, 0, 1, 0,
                    MonsterRole.ZOMBIE, MonsterDimension.OVERWORLD, 0, 0, Map.of(), MonsterWallet.EMPTY, 0, true));
            s.put(second.id(), second.name(), ParticipantRole.HUMAN_ACTIVE, 2, 0, 0, true);
            int humanBars = human.bars.size(), campBars = camp.bars.size();
            var single = TotemDrops.prepare(second.player, s.snapshot(), s.melee(killer.id())).orElseThrow();
            TotemDrops.captured(second.player, s.roundId, single, true);
            s.commit(() -> {
                s.put(second.id(), second.name(), ParticipantRole.MONSTER_LOBBY, 2, 0, 0, true);
                s.register(single, second.id(), 2, killer.id());
            });
            test.assertValueEqual(late.bars, List.of(second.name() + " 被人类击杀，掉落了复生图腾"), "A single victim is named alone");
            test.assertValueEqual(entering.bars, late.bars, "A player still entering is told too");
            test.assertTrue(human.bars.size() == humanBars && camp.bars.size() == campBars, "Everyone else was already told this round");

            // However many ticks and however much race time a drop lives through, it stays while registered (守恒 1.3).
            var aged = f.registered(s, Vec3.atBottomCenterOf(f.base.offset(6, 0, -6)));
            for (int i = 0; i < 13_000; i++) aged.tick();
            s.challenge += 3L * 3_600 * 1_000_000_000L;
            TotemDrops.patrol(f.server);
            test.assertTrue(!aged.isRemoved() && aged.getAge() == UNLIMITED_LIFETIME, "Thirteen thousand ticks and three hours keep it");

            // The void returns a live drop: first to where it was dropped, then (it fell again) to the arrival point.
            var dropped = aged.position();
            aged.setPos(aged.getX(), f.level.getMinY() - 70, aged.getZ());
            aged.tick();
            test.assertTrue(!aged.isRemoved() && aged.position().distanceTo(dropped) < 1, "Back where it was dropped: " + aged.position());
            aged.setPos(aged.getX(), f.level.getMinY() - 70, aged.getZ());
            aged.tick();
            var spawn = Vec3.atBottomCenterOf(f.level.getRespawnData().pos());
            test.assertTrue(aged.position().distanceTo(spawn) < 1, "Then to the arrival point: " + aged.position());
            var unknown = new ItemEntity(f.level, f.base.getX() + .5, f.level.getMinY() - 70, f.base.getZ() + .5,
                    RevivalTotems.held(new Identity(s.roundId, ParticipantRole.HUMAN_ACTIVE, 1, 0), 1, 1));
            f.level.addFreshEntity(unknown);
            unknown.tick();
            test.assertTrue(unknown.isRemoved(), "A copy without a live token falls out of the world as vanilla's void decides");
            aged.discard();

            // The patrol: a registration that is gone, a token-less copy and a pending drop of a failed session go.
            var forgotten = f.registered(s, Vec3.atBottomCenterOf(f.base.offset(4, 0, -4)));
            var stray = new ItemEntity(f.level, f.base.getX() + 2.5, f.base.getY(), f.base.getZ() - 4.5,
                    RevivalTotems.held(new Identity(s.roundId, ParticipantRole.HUMAN_ACTIVE, 1, 0), 1, 1));
            f.level.addFreshEntity(stray);
            s.registry.remove(RevivalTotems.dropToken(forgotten.getItem()).orElseThrow());
            TotemDrops.patrol(f.server);
            test.assertTrue(forgotten.isRemoved() && stray.isRemoved(), "Unregistered and token-less floor totems are removed");
            test.assertFalse(refs.other.isRemoved(), "A live registration stays");
            killer.move(f.base.offset(-3, 0, 4));
            s.put(killer.id(), killer.name(), ParticipantRole.HUMAN_ACTIVE, 1, 0, 0, true);
            s.put(human.id(), human.name(), ParticipantRole.HUMAN_ACTIVE, 1, 3, 0, true);
            var orphan = TotemDrops.prepare(human.player, s.snapshot(), s.melee(killer.id())).orElseThrow();
            TotemDrops.captured(human.player, s.roundId, orphan, true);
            var orphaned = only(test, f.drops(orphan), "Pending drop");
            s.open = false;
            TotemDrops.patrol(f.server);
            test.assertTrue(orphaned.isRemoved(), "A pending drop whose session failed is removed");
            s.open = true;

            // A totem a human forced out of a living human's hand (design 1.5): the same test as a death, pending while
            // its save is in flight whatever commits in between, removed when its save commits without registering it,
            // announced on its own line, once a round, when registered.
            s.put(second.id(), second.name(), ParticipantRole.HUMAN_ACTIVE, 3, 1, 0, true);
            for (var credit : List.of(s.melee(second.id()), Optional.<CombatOrigin>empty())) {
                var saveToken = TotemDrops.prepareSave(second.player, credit);
                test.assertTrue(saveToken.isPresent(), "One's own hit or the environment forces out an unclaimed totem: " + credit);
                TotemDrops.captured(second.player, s.roundId, saveToken.get(), false);
            }
            var refusedSave = TotemDrops.prepareSave(second.player, s.melee(killer.id())).orElseThrow();
            TotemDrops.captured(second.player, s.roundId, refusedSave, false);
            test.assertTrue(f.drops(refusedSave).isEmpty(), "A save the session refused drops nothing");
            var voided = TotemDrops.prepareSave(second.player, s.melee(killer.id())).orElseThrow();
            s.inFlight.add(voided);
            TotemDrops.captured(second.player, s.roundId, voided, true);
            var voidedItem = only(test, f.drops(voided), "A booked save drops one totem");
            test.assertTrue(voidedItem.position().distanceTo(second.player.position()) < 2, "At the saved human's feet");
            s.commit(() -> {});
            TotemDrops.patrol(f.server);
            test.assertFalse(voidedItem.isRemoved(), "Pending while its save is in flight, its human still alive");
            int lateTold = late.bars.size();
            s.commit(() -> s.inFlight.remove(voided));
            test.assertTrue(voidedItem.isRemoved(), "Removed once its save commits without registering it");
            test.assertValueEqual(late.bars.size(), lateTold, "Nothing is announced for an unregistered forced drop");
            var forcedToken = TotemDrops.prepareSave(second.player, s.melee(killer.id())).orElseThrow();
            s.inFlight.add(forcedToken);
            TotemDrops.captured(second.player, s.roundId, forcedToken, true);
            s.commit(() -> { s.inFlight.remove(forcedToken); s.register(forcedToken, second.id(), 3, killer.id()); });
            test.assertFalse(only(test, f.drops(forcedToken), "The forced-out drop").isRemoved(), "A registered forced drop stays");
            String forced = Notices.format(TotemDrops.forcedLine(second.name(), 1)).getString();
            test.assertValueEqual(late.bars.getLast(), forced, "Told on its own line, though the death line was told already");
            test.assertValueEqual(entering.bars.getLast(), forced, "A player still entering is told too");
            test.assertTrue(killer.bars.stream().noneMatch(forced::equals) && second.bars.stream().noneMatch(forced::equals),
                    "The attacker and the saved human are not told");
            // The saved life never takes its own totem back (守恒 1.4); it is told once, and anyone else may take it.
            s.challenge += TotemDrops.PICKUP_DELAY_NANOS;
            var forcedItem = only(test, f.drops(forcedToken), "The forced-out drop");
            int askedOwn = s.requests;
            forcedItem.playerTouch(second.player);
            forcedItem.playerTouch(second.player);
            test.assertValueEqual(s.requests, askedOwn, "The saved human never asks for its own totem");
            test.assertValueEqual(second.bars.stream().filter(Notices.format(TotemDrops.OWN_TIP).getString()::equals).count(), 1L,
                    "Told once that it cannot take it back");
            test.assertFalse(forcedItem.isRemoved(), "It still lies there for others");

            // An unclaimed drop tells everyone still playing where it lies, every time, the victim included.
            s.put(late.id(), late.name(), ParticipantRole.HUMAN_ACTIVE, 1, 0, 0, true);
            for (var c : f.clients) c.chat().clear();
            var accident = TotemDrops.prepare(late.player, s.snapshot(), Optional.empty()).orElseThrow();
            var where = TotemDrops.place(late.player);
            TotemDrops.captured(late.player, s.roundId, accident, true);
            s.commit(() -> {
                s.put(late.id(), late.name(), ParticipantRole.MONSTER_LOBBY, 1, 0, 0, true);
                s.registry.put(accident, new LooseTotem(late.id(), 1, LooseTotem.Source.UNCLAIMED, Optional.empty(), s.challenge));
            });
            String coordinates = Notices.format(TotemDrops.unclaimedLine(late.name(), where)).getString();
            test.assertTrue(coordinates.contains("主世界") && coordinates.contains("(" + BlockPos.containing(where.at()).getX() + ", "),
                    "The line names the dimension and the block: " + coordinates);
            for (var c : List.of(human, camp, deployed, killer, late, entering, second))
                test.assertValueEqual(c.chat(), List.of(coordinates), "Told in chat: " + c.name());
            for (var c : List.of(withdrawn, outsider)) test.assertValueEqual(c.chat().size(), 0, "Not told: " + c.name());
            s.put(late.id(), late.name(), ParticipantRole.HUMAN_ACTIVE, 2, 0, 0, true);
            var again = TotemDrops.prepare(late.player, s.snapshot(), Optional.empty()).orElseThrow();
            TotemDrops.captured(late.player, s.roundId, again, true);
            s.commit(() -> {
                s.put(late.id(), late.name(), ParticipantRole.MONSTER_LOBBY, 2, 0, 0, true);
                s.registry.put(again, new LooseTotem(late.id(), 2, LooseTotem.Source.UNCLAIMED, Optional.empty(), s.challenge));
            });
            test.assertValueEqual(human.chat().size(), 2, "Every unclaimed drop is told, not once a round");

            // A new round: the empty registry clears the last round's drops, and the team is sent afresh.
            for (var c : f.clients) c.teams.clear();
            s.roundId = UUID.randomUUID();
            s.registry.clear();
            TotemDrops.tick(f.server);
            TotemDrops.patrol(f.server);
            test.assertTrue(refs.other.isRemoved() && f.registered.stream().allMatch(ItemEntity::isRemoved), "A new round clears every drop");
            test.assertTrue(human.team(p -> p.getTeamAction() == ClientboundSetPlayerTeamPacket.Action.REMOVE)
                    && human.team(p -> p.getTeamAction() == ClientboundSetPlayerTeamPacket.Action.ADD && p.getPlayers().isEmpty()),
                    "The team is rebuilt empty for the new round");
        }).thenIdle(1).thenSucceed();
    }

    /** Sets vanilla's private {@code startingToFallPosition}, which only a real fall would record. */
    private static void fallStart(ServerPlayer player, Vec3 at) {
        try {
            var field = ServerPlayer.class.getDeclaredField("startingToFallPosition");
            field.setAccessible(true);
            field.set(player, at);
        } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }

    private static ItemEntity only(GameTestHelper test, List<ItemEntity> items, String what) {
        test.assertValueEqual(items.size(), 1, what);
        return items.getFirst();
    }

    private static void require(boolean condition, String what) {
        if (!condition) throw new GameTestAssertException(Component.literal("Waiting until " + what), 0);
    }

    private static int total(ServerPlayer player) {
        int count = 0;
        var inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) if (RevivalTotems.is(inventory.getItem(i))) count += inventory.getItem(i).getCount();
        return count;
    }

    /** The round runtime as the floor totems see it: a scripted committed round, its registry and its commits. */
    private static final class Script implements TotemDrops.Access {
        final MinecraftServer server;
        UUID roundId = UUID.randomUUID();
        RulesConfig rules = RulesConfig.defaults();
        final Map<UUID, Participant> players = new LinkedHashMap<>();
        final Map<UUID, LooseTotem> registry = new LinkedHashMap<>();
        final Set<UUID> collectors = new HashSet<>();
        /** Drop tokens of saves the scripted session still holds. */
        final Set<UUID> inFlight = new HashSet<>();
        long challenge = 10L * 60 * 1_000_000_000L;
        long version;
        boolean open = true, defer;
        int requests;
        private Runnable deferred;
        private Consumer<RoundSession.Reply> deferredReply;

        Script(MinecraftServer server) { this.server = server; }

        void put(UUID id, String name, ParticipantRole role, int life, int charges, int sortie, boolean online) {
            players.put(id, new Participant(id, name, role, online, false, 0, 0, life, charges,
                    MonsterRole.ZOMBIE, MonsterDimension.OVERWORLD, 0, sortie, Map.of(), MonsterWallet.EMPTY, 0, false));
        }

        void register(UUID token, UUID victim, int life, UUID killer) {
            registry.put(token, new LooseTotem(victim, life, LooseTotem.Source.HUMAN, Optional.of(killer), challenge));
        }

        Optional<CombatOrigin> melee(UUID attacker) {
            var p = players.get(attacker);
            return Optional.of(new CombatOrigin(roundId, UUID.randomUUID(), new CombatActor(attacker, p.role(), p.lifeSequence(),
                    p.monsterDeploymentSequence()), AttackKind.MELEE, Optional.empty(), List.of()));
        }

        RoundSnapshot snapshot() {
            return new RoundSnapshot(roundId, 1, 1, version, rules, RoundPhase.RUNNING, PauseReason.NONE, challenge, 0,
                    new LinkedHashMap<>(players), false, false, null, new LinkedHashMap<>(registry));
        }

        /** A committed transition, then the commit listener as {@code RoundRuntime.applyPlayers} calls it. */
        void commit(Runnable change) {
            var before = snapshot();
            change.run();
            version++;
            TotemDrops.onCommitted(server, before, snapshot());
        }

        void answer(boolean success) {
            var reply = deferredReply;
            deferredReply = null;
            if (success) deferred.run();
            else reply.accept(new RoundSession.Reply(false, "refused"));
        }

        @Override public Optional<RoundSnapshot> view() { return Optional.of(snapshot()); }
        @Override public boolean open() { return open; }
        @Override public boolean collects(ServerPlayer player) {
            var p = players.get(player.getUUID());
            return collectors.contains(player.getUUID()) && p != null && p.role() == ParticipantRole.HUMAN_ACTIVE;
        }
        @Override public void refresh(ServerPlayer player) {
            var p = players.get(player.getUUID());
            // A monster out hunting holds no copies; its balance shows once it is back in camp.
            if (p.role() == ParticipantRole.MONSTER_DEPLOYED) return;
            RevivalTotems.project(player, new Decision(State.PROJECT, Identity.of(roundId, p), p.revivalCharges()));
        }
        @Override public boolean saveInFlight(UUID token) { return inFlight.contains(token); }
        @Override public boolean hunts(ServerPlayer player) {
            var p = players.get(player.getUUID());
            return p != null && p.role() == ParticipantRole.MONSTER_DEPLOYED;
        }
        @Override public void requestPickup(UUID round, UUID token, UUID player, int life, int sortie, Consumer<RoundSession.Reply> reply) {
            requests++;
            Runnable apply = () -> {
                var p = players.get(player);
                boolean taker = p != null && (p.role() == ParticipantRole.HUMAN_ACTIVE
                        || p.role() == ParticipantRole.MONSTER_DEPLOYED && p.monsterDeploymentSequence() == sortie);
                if (!round.equals(roundId) || !registry.containsKey(token) || !taker || p.lifeSequence() != life
                        || registry.get(token).ownedBy(p)) { reply.accept(new RoundSession.Reply(false, "refused")); return; }
                commit(() -> {
                    registry.remove(token);
                    put(player, p.name(), p.role(), p.lifeSequence(), p.revivalCharges() + 1, p.monsterDeploymentSequence(), p.online());
                });
                reply.accept(new RoundSession.Reply(true, "ok"));
            };
            if (defer) { deferred = apply; deferredReply = reply; }
            else apply.run();
        }
    }

    private record Client(ServerPlayer player, GameProfile profile, Recorder connection, EmbeddedChannel channel,
                          List<String> bars, List<String> chat, List<ClientboundSetPlayerTeamPacket> teams) {
        UUID id() { return profile.id(); }
        String name() { return profile.name(); }
        boolean team(java.util.function.Predicate<ClientboundSetPlayerTeamPacket> test) {
            return teams.stream().anyMatch(p -> TotemDrops.TEAM.equals(p.getName()) && test.test(p));
        }
        void move(BlockPos at) { move(Vec3.atBottomCenterOf(at)); }
        void move(Vec3 at) { player.teleportTo(at.x, at.y, at.z); }
    }

    private static final class Fixture {
        final ServerLevel level;
        final MinecraftServer server;
        final BlockPos base;
        final int arena = ARENAS.getAndIncrement();
        final List<Client> clients = new ArrayList<>();
        final List<ItemEntity> registered = new ArrayList<>();
        List<Object> keep = List.of();

        Fixture(GameTestHelper test) {
            level = test.getLevel();
            server = level.getServer();
            base = new BlockPos(40000 + arena * 64, 150, 3000);
            level.setChunkForced(base.getX() >> 4, base.getZ() >> 4, true);
            for (int dx = -12; dx <= 12; dx++) for (int dz = -12; dz <= 12; dz++) {
                level.setBlockAndUpdate(base.offset(dx, -1, dz), Blocks.SMOOTH_STONE.defaultBlockState());
                for (int dy = 0; dy < 4; dy++) level.setBlockAndUpdate(base.offset(dx, dy, dz), Blocks.AIR.defaultBlockState());
            }
            for (int cx = -1; cx <= 1; cx++) for (int cz = -1; cz <= 1; cz++)
                level.setChunkForced((base.getX() >> 4) + cx, (base.getZ() >> 4) + cz, true);
        }

        Client client(String tag) {
            var profile = new GameProfile(UUID.randomUUID(), "HRDrop" + tag + arena);
            var bars = new ArrayList<String>();
            var chat = new ArrayList<String>();
            var teams = new ArrayList<ClientboundSetPlayerTeamPacket>();
            var connection = new Recorder(bars, chat, teams);
            var player = new ServerPlayer(server, level, profile, TestClients.information());
            var channel = new EmbeddedChannel(connection);
            dev.hardrush.gametest.UnmanagedProfileFixtures.run(() -> server.getPlayerList().placeNewPlayer(connection, player,
                    TestClients.cookie(profile)));
            player.teleportTo(base.getX() - 10 + clients.size() * 2 + .5, base.getY(), base.getZ() - 11 + .5);
            player.setGameMode(GameType.SURVIVAL);
            player.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
            bars.clear(); chat.clear(); teams.clear();
            var client = new Client(player, profile, connection, channel, bars, chat, teams);
            clients.add(client);
            return client;
        }

        void awaitArena() { require(level.isPositionEntityTicking(base), "the arena simulates entities"); }

        List<ItemEntity> drops(UUID token) {
            var found = new ArrayList<ItemEntity>();
            for (var world : server.getAllLevels()) found.addAll(world.getEntities(EntityTypeTest.forClass(ItemEntity.class),
                    item -> RevivalTotems.dropToken(item.getItem()).filter(token::equals).isPresent()));
            return found;
        }

        /** A drop already registered in the scripted round, as if its batch had committed. */
        ItemEntity registered(Script s, Vec3 at) {
            var token = UUID.randomUUID();
            s.register(token, UUID.randomUUID(), 1, UUID.randomUUID());
            var item = TotemDrops.spawn(new TotemDrops.Place(level, at), token, s.roundId);
            item.setDeltaMovement(Vec3.ZERO);
            registered.add(item);
            return item;
        }

        <T extends Mob> T mob(T mob, BlockPos at) {
            mob.setPos(Vec3.atBottomCenterOf(at));
            mob.setNoAi(true);
            mob.setCanPickUpLoot(true);
            mob.setPersistenceRequired();
            mob.addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 2000));
            level.addFreshEntity(mob);
            return mob;
        }

        void close() {
            for (var o : keep) ((net.minecraft.world.entity.Entity) o).discard();
            for (var c : clients) {
                var current = server.getPlayerList().getPlayer(c.id());
                if (current != null) server.getPlayerList().remove(current);
                c.channel().finishAndReleaseAll();
            }
            for (int cx = -1; cx <= 1; cx++) for (int cz = -1; cz <= 1; cz++)
                level.setChunkForced((base.getX() >> 4) + cx, (base.getZ() >> 4) + cz, false);
        }
    }

    /** Keeps the action-bar lines, chat lines and glow-team packets a fixture player receives. */
    private static final class Recorder extends Connection {
        final List<String> bars;
        final List<String> chat;
        final List<ClientboundSetPlayerTeamPacket> teams;
        Recorder(List<String> bars, List<String> chat, List<ClientboundSetPlayerTeamPacket> teams) {
            super(PacketFlow.SERVERBOUND);
            this.bars = bars; this.chat = chat; this.teams = teams;
        }
        private void record(Packet<?> packet) {
            if (packet instanceof ClientboundSetActionBarTextPacket bar) bars.add(bar.text().getString());
            if (packet instanceof net.minecraft.network.protocol.game.ClientboundSystemChatPacket line && !line.overlay())
                chat.add(line.content().getString());
            if (packet instanceof ClientboundSetPlayerTeamPacket team && TotemDrops.TEAM.equals(team.getName())) teams.add(team);
        }
        @Override public void send(Packet<?> packet) { record(packet); }
        @Override public void send(Packet<?> packet, ChannelFutureListener listener) { record(packet); }
        @Override public void send(Packet<?> packet, ChannelFutureListener listener, boolean flush) { record(packet); }
    }
}
