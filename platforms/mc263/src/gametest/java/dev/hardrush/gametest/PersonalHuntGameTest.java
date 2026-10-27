package dev.hardrush.gametest;

import com.mojang.authlib.GameProfile;
import dev.hardrush.core.api.MonotonicClock;
import dev.hardrush.core.lobby.LobbyLayout;
import dev.hardrush.core.round.*;
import dev.hardrush.monster.MonsterRoles;
import dev.hardrush.round.RoundRuntime;
import dev.hardrush.round.RoundSession;
import dev.hardrush.lobby.LobbyInteractions;
import dev.hardrush.player.PlayerProfiles;
import dev.hardrush.world.LobbyWorld;
import dev.hardrush.world.RoundWorldBundle;
import dev.hardrush.lobby.LobbyRuntime;
import dev.hardrush.protection.ProtectionRuntime;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.*;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.config.PrepareSpawnTask;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.Vec3;

/** Four native embedded connections, real SQLite/profile IO, test arenas and controlled clocks. */
public final class PersonalHuntGameTest {
    @GameTest(maxTicks = 60000)
    public void personalModelsDimensionsBudgetsAndCommitRaces(GameTestHelper test) {
        if (!Boolean.getBoolean(TestProperties.PERSONAL_HUNT)) { test.succeed(); return; }
        var f = new Fixture(test);
        test.onEachTick(f::tick);
        var s = test.startSequence().thenWaitUntil(() -> f.await(RoundRuntime.sessionState(f.server)
                .filter(v -> v == RoundSession.State.OPEN).isPresent() && LobbyWorld.open(f.server).ready(), "bootstrap"))
                .thenExecute(f::bind);
        for (var p : f.peers) p.login(s);
        s.thenWaitUntil(() -> f.await(f.round().phase() == RoundPhase.WAITING && f.idle(), "worlds ready"))
                .thenExecute(() -> f.peers.forEach(p -> SpatialInputFixtures.handle(p.player, "hardrush ready")))
                .thenWaitUntil(() -> f.await(f.peers.stream().allMatch(p -> p.participant() != null && p.participant().ready()) && f.idle(), "four spatial ready"))
                .thenExecute(() -> f.clock.addAndGet(TimeUnit.SECONDS.toNanos(31)))
                .thenWaitUntil(() -> f.await(f.round().phase() == RoundPhase.RUNNING && f.idle()
                        && f.peers.stream().allMatch(p -> !PlayerProfiles.isLocked(p.player)), "four admitted humans"))
                .thenExecute(f::arenas)
                .thenWaitUntil(() -> f.await(f.arenasTicking(), "three arenas ticking"))
                .thenExecute(() -> { f.move(f.a, 0); f.move(f.b, 1); f.move(f.c, 0); f.move(f.d, 0); f.kill(f.c); })
                .thenWaitUntil(() -> f.await(f.lobby(f.c), "C hardcore death lobby"))
                .thenExecute(() -> f.kill(f.d))
                .thenWaitUntil(() -> f.await(f.lobby(f.d), "D hardcore death lobby"))
                .thenExecute(() -> {
                    f.c.player = f.c.current(); f.d.player = f.d.current();
                    f.refresh(); f.assertModels();
                    f.c.hunt(f.a); test.assertTrue(f.searches().isEmpty(), "Cooldown click cannot reserve automatic departure");
                    f.clock.addAndGet(TimeUnit.SECONDS.toNanos(31));
                })
                .thenWaitUntil(() -> f.await(f.idle() && f.c.participant().monsterCooldownNanos() == 0, "cooldown elapsed without automatic request"))
                .thenExecute(() -> {
                    test.assertTrue(f.searches().isEmpty(), "Cooldown zero never auto-deploys");
                    f.c.role("skeleton");
                })
                .thenWaitUntil(() -> f.await(f.idle() && f.c.participant().monsterRole() == MonsterRole.SKELETON, "physical role booth"))
                .thenExecute(() -> {
                    var selected = HuntInputFixtures.model(f.c.player, f.a.id(), f.c.connection.packets);
                    HuntInputFixtures.click(f.c.player, selected.entityId(), InteractionHand.OFF_HAND);
                    test.assertTrue(f.searches().isEmpty(), "Offhand model packet cannot start search");
                    HuntInputFixtures.click(f.c.player, selected.entityId(), InteractionHand.MAIN_HAND);
                    for (int i = 0; i < 8; i++) HuntInputFixtures.click(f.c.player, selected.entityId(), InteractionHand.MAIN_HAND);
                    test.assertValueEqual(f.searches().size(), 1, "Rapid model clicks have exactly one request");
                    f.d.hunt(f.b);
                    test.assertValueEqual(f.searches().size()+f.pending().size(), 2, "Different monsters independently select different humans, including already submitted searches");
                })
                .thenWaitUntil(() -> f.await(f.deployed(f.c, 0) && f.deployed(f.d, 1), "independent overworld and nether deployments"))
                .thenExecute(() -> {
                    test.assertValueEqual(f.c.participant().monsterDeploymentSequence(), 1, "No duplicate sortie");
                    f.move(f.a, 2);
                    test.assertTrue(f.c.player.level() == f.levels[0], "Deployed monster never follows target dimension changes");
                    f.kill(f.c); f.kill(f.d);
                })
                .thenWaitUntil(() -> f.await(f.lobby(f.c) && f.lobby(f.d), "both defeated back in camp"))
                .thenExecute(() -> {
                    f.c.player = f.c.current(); f.d.player = f.d.current(); f.clock.addAndGet(TimeUnit.SECONDS.toNanos(31));
                })
                .thenWaitUntil(() -> f.await(f.idle() && f.c.participant().monsterCooldownNanos() == 0 && f.d.participant().monsterCooldownNanos() == 0, "second cooldown"))
                .thenExecute(() -> { f.c.hunt(f.a); f.d.hunt(f.a); })
                .thenWaitUntil(() -> f.await(f.deployed(f.c, 2) && f.deployed(f.d, 2), "both select same End human without quota"))
                .thenExecute(() -> { f.kill(f.c); f.kill(f.d); })
                .thenWaitUntil(() -> f.await(f.lobby(f.c) && f.lobby(f.d), "budget case camp"))
                .thenExecute(() -> {
                    f.c.player = f.c.current(); f.d.player = f.d.current(); f.clock.addAndGet(TimeUnit.SECONDS.toNanos(31));
                    f.useSearchClock(); f.moveVoid(f.a, 1);
                })
                .thenWaitUntil(() -> f.await(f.idle() && f.c.participant().monsterCooldownNanos() == 0, "budget cooldown"))
                .thenExecute(() -> {
                    f.c.hunt(f.a); f.refresh();
                    var request = f.searches().get(f.c.id()); f.originalAttempt = request;
                    f.started = (long)f.field(request, "started");
                    int used = f.candidates(request); test.assertTrue(used > 0 && used <= 64, "Actual search evaluates bounded candidates");
                    f.moveVoid(f.a, 0); f.refresh();
                    test.assertTrue(f.searches().get(f.c.id()) == request, "Dimension switch keeps the exact request");
                    test.assertTrue((long)f.field(request, "started") == f.started && (int)f.field(request, "spentCandidates") >= used,
                            "Dimension switch retains deadline and spent candidates");
                    f.searchClock.addAndGet(2_000_000_000L); f.refresh();
                    test.assertTrue(f.searches().isEmpty() && f.candidates(request) <= 64, "All dimensions share two seconds and 64 candidates");
                    f.c.hunt(f.b); test.assertTrue(f.searches().isEmpty(), "Changing target cannot bypass UUID retry wait");
                    f.move(f.a, 0); f.searchClock.addAndGet(3_000_000_000L);
                });
        s.thenExecute(() -> {
            f.moveVoid(f.a,1); f.c.hunt(f.a); var request=f.searches().get(f.c.id());
            for(int i=0; i<80 && f.searches().containsKey(f.c.id()); i++) {
                if(i%3==0) f.moveVoid(f.a,i%2); f.refresh();
            }
            test.assertTrue(f.searches().isEmpty(), "Shared candidate allowance eventually ends search across dimensions");
            test.assertValueEqual(f.candidates(request),64,"Exactly 64 total candidates, never 64 per dimension");
            f.searchClock.addAndGet(3_000_000_000L); f.move(f.a,0);
        });
        // Changed terrain and target movement AFTER real SQLite submission, BEFORE the native callback.
        f.commitRace(s, false); f.commitRace(s, true);
        s.thenExecute(() -> f.c.role("zombie"))
                .thenWaitUntil(() -> f.await(f.idle() && f.c.participant().monsterRole()==MonsterRole.ZOMBIE,"physical role for non-anchor melee"))
                .thenExecute(() -> {
                    f.useSearchClock(); f.moveVoid(f.a, 1); f.c.hunt(f.a);
                    f.oldModel = f.model(f.c, f.a); f.a.disconnect(); f.refresh();
                    test.assertTrue(f.searches().isEmpty(), "Disconnect cancels instead of choosing another live human");
                    f.assertAway();
                });
        f.a.login(s);
        s.thenWaitUntil(() -> f.await(f.idle() && !PlayerProfiles.isLocked(f.a.player), "human reconnect"))
                .thenExecute(() -> {
                    f.searchClock.addAndGet(3_000_000_000L); f.refresh();
                    HuntInputFixtures.click(f.c.player, f.oldModel, InteractionHand.MAIN_HAND);
                    test.assertTrue(f.searches().isEmpty(), "Old page before reconnect never selects replacement mapping");
                    f.move(f.a, 0); f.move(f.b, 0); f.b.player.teleportTo(f.levels[0], f.anchor[0].x + 10, f.anchor[0].y, f.anchor[0].z, Set.of(), 0,0,true);
                    f.c.hunt(f.a); f.refresh();
                })
                .thenWaitUntil(() -> f.await(f.deployed(f.c, 0), "hunt anchor A before attacking B"))
                .thenExecute(() -> {
                    // Choice is only a spawn anchor; a DIFFERENT legal human still earns entitlement.
                    dev.hardrush.pact.DawnPactRuntime.tick(f.server);
                    var overworld = f.worlds.level(Level.OVERWORLD);
                    var daylight = overworld.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.WORLD_CLOCK)
                            .getOrThrow(net.minecraft.world.clock.WorldClocks.OVERWORLD);
                    overworld.clockManager().setTotalTicks(daylight, dev.hardrush.core.combat.DawnPact.SUNRISE_TICKS + 18_000);
                    dev.hardrush.pact.DawnPactRuntime.tick(f.server);
                    f.c.player.teleportTo(f.b.player.level(), f.b.player.getX()+1, f.b.player.getY(), f.b.player.getZ(), Set.of(),0,0,true);
                    f.b.player.setHealth(1); f.b.player.setInvulnerableTime(0);
                    // Birth gear can be a spear; this regression exercises ordinary native melee.
                    f.c.player.getInventory().setItem(8, net.minecraft.world.item.ItemStack.EMPTY);
                    f.c.player.getInventory().setSelectedSlot(8); MonsterRoles.repairLoadout(f.c.player);
                    test.assertFalse(dev.hardrush.pact.DawnPactRuntime.reject(f.c.player, f.b.player), "The first sunrise releases player combat before entitlement checks");
                    test.assertTrue(MonsterRoles.attack(f.c.player, f.b.player) && f.b.player.isDeadOrDying(), "Attack another legal human");
                })
                .thenWaitUntil(() -> f.await(f.c.participant().revivalEntitled() && f.lobby(f.b), "actual non-anchor kill awards entitlement"))
                .thenExecute(() -> {
                    f.b.player = f.b.current();
                    f.kill(f.c);
                })
                .thenWaitUntil(() -> f.await(f.lobby(f.c), "entitled hunter defeated"))
                .thenExecute(() -> {
                    f.c.player = f.c.current(); f.clock.addAndGet(TimeUnit.SECONDS.toNanos(31)); f.useSearchClock();
                    f.moveVoid(f.a, 1);
                })
                .thenWaitUntil(() -> f.await(f.idle() && f.c.participant().monsterCooldownNanos() == 0, "death-life setup"))
                .thenExecute(() -> {
                    f.c.hunt(f.a); f.oldAttempt = f.searches().get(f.c.id());
                    f.oldModel = f.model(f.c, f.a); f.kill(f.a);
                })
                .thenWaitUntil(() -> f.await(f.lobby(f.a), "anchor death cancels old life"))
                .thenExecute(() -> {
                    test.assertTrue(f.searches().isEmpty(), "Death cancels the original request"); f.a.player=f.a.current();
                    // Existing revival service is fixture setup; round 4 still owns the altar entrance.
                    f.reply.set(null); var p=f.c.participant();
                    f.session.requestRevival(f.round().roundId(), f.c.id(), p.lifeSequence(), p.monsterDeploymentSequence(), f.reply::set);
                })
                .thenWaitUntil(() -> f.await(f.reply.get()!=null && f.reply.get().success() && f.idle() && !PlayerProfiles.isLocked(f.c.player), "fixture C revives using actual earned entitlement"))
                .thenExecute(() -> { f.move(f.c,0); f.clock.addAndGet(TimeUnit.SECONDS.toNanos(31)); f.searchClock.addAndGet(3_000_000_000L); f.a.role("zombie"); })
                .thenWaitUntil(() -> f.await(f.idle() && f.a.participant().monsterCooldownNanos()==0, "A monster setup"))
                .thenExecute(() -> f.a.hunt(f.c))
                .thenWaitUntil(() -> f.await(f.deployed(f.a,0), "A hunts restored C"))
                .thenExecute(() -> {
                    f.a.player.teleportTo(f.c.player.level(),f.c.player.getX()+1,f.c.player.getY(),f.c.player.getZ(),Set.of(),0,0,true);
                    f.c.player.setHealth(1); f.c.player.setInvulnerableTime(0);
                    f.a.player.getInventory().setItem(8, net.minecraft.world.item.ItemStack.EMPTY);
                    f.a.player.getInventory().setSelectedSlot(8); MonsterRoles.repairLoadout(f.a.player);
                    test.assertTrue(MonsterRoles.attack(f.a.player,f.c.player) && f.c.player.isDeadOrDying(), "A earns real entitlement by native lethal attack");
                })
                .thenWaitUntil(() -> f.await(f.idle() && f.a.participant().revivalEntitled() && f.lobby(f.c), "A entitlement persisted"))
                .thenExecute(() -> { f.c.player=f.c.current(); f.kill(f.a); })
                .thenWaitUntil(() -> f.await(f.lobby(f.a), "A defeated with entitlement"))
                .thenExecute(() -> { f.a.player=f.a.current(); f.reply.set(null); var p=f.a.participant();
                    f.session.requestRevival(f.round().roundId(), f.a.id(), p.lifeSequence(), p.monsterDeploymentSequence(), f.reply::set); })
                .thenWaitUntil(() -> f.await(f.reply.get()!=null && f.reply.get().success() && f.idle() && !PlayerProfiles.isLocked(f.a.player), "same UUID new human life"))
                .thenExecute(() -> {
                    test.assertTrue(f.a.participant().lifeSequence() == 2, "Actual revival increments life");
                    test.assertValueEqual(f.restoringShows,2,"Both new-life admissions expose restoring status before availability");
                    var identity = (dev.hardrush.core.hunt.PersonalHuntRequest)f.field(f.oldAttempt,"identity");
                    test.assertFalse(identity.targetMatches(f.round()), "Original request cannot match revived same UUID");
                    f.refresh(); HuntInputFixtures.click(f.c.player, f.oldModel, InteractionHand.MAIN_HAND);
                    test.assertTrue(f.searches().isEmpty(), "Old model cannot chase revived life");
                });
        // Only these AWAY roster entries are pre-admitted fixtures, never claimed as native players.
        for(int i=0;i<18;i++) {
            final int slot=i;
            s.thenExecute(() -> { f.reply.set(null); f.session.requestJoin(UUID.randomUUID(),"AwayFixture"+slot,f.reply::set); })
                    .thenWaitUntil(() -> f.await(f.reply.get()!=null && f.reply.get().success() && f.idle(),"offline display fixture "+slot));
        }
        s.thenExecute(f::pages)
                .thenExecute(() -> { f.oldBody=f.c.player; f.oldModel=f.model(f.c,f.a); f.c.disconnect(); });
        f.c.login(s);
        s.thenWaitUntil(() -> f.await(f.idle() && f.c.participant().online(),"reconnected monster online before clock advance"))
                .thenExecute(() -> {
                    f.refresh(); HuntInputFixtures.click(f.oldBody,f.oldModel,InteractionHand.MAIN_HAND);
                    HuntInputFixtures.click(f.c.player,f.oldModel,InteractionHand.MAIN_HAND);
                    test.assertTrue(f.searches().isEmpty(),"Old connection object and ID cannot enter a replacement body");
                    f.clock.addAndGet(TimeUnit.SECONDS.toNanos(31)); f.searchClock.addAndGet(3_000_000_000L); f.move(f.a,0);
                    f.prepareFault();
                })
                .thenWaitUntil(() -> f.await(f.ioTask.isDone() && f.idle() && f.c.participant().monsterCooldownNanos()==0,"fault fixture ready"))
                .thenExecute(f::profileFault)
                .thenWaitUntil(() -> f.await(f.session.state() == RoundSession.State.FAILED, "controlled fault isolates"))
                .thenExecute(() -> {
                    f.refresh(); test.assertTrue(f.searches().isEmpty(), "Fault never reports or applies a successful hunt");
                    test.assertValueEqual(f.c.participant().monsterDeploymentSequence(),f.faultSortie,"Fault cannot publish sortie success");
                    test.assertTrue(f.c.player.level()==LobbyWorld.open(f.server).holdingLevel(),"Fault holds the native hunter in camp");
                    test.assertTrue(LobbyInteractions.get(f.server).orElseThrow().projections().view(f.c.player).isEmpty(), "Fault removes actionable models");
                    f.peers.forEach(p -> { if(p.current()!=null) p.disconnect(); });
                    System.out.println("PERSONAL_HUNT_PASS: four native connections + 18 admitted AWAY display fixtures; physical booths/models/pages; three dimensions; independent and shared anchors; cooldown/no queue; double hand/spam; shared budgets; post-commit movement/terrain refunds; disconnect/reconnect and stale connection; death/revival life; other-human kill; fault="+System.getProperty(TestProperties.PERSONAL_HUNT_FAULT,"adapter"));
                }).thenSucceed();
    }

    private static final class Fixture {
        final GameTestHelper test; final MinecraftServer server;
        final Peer a = new Peer("HuntA"), b = new Peer("HuntB"), c = new Peer("HuntC"), d = new Peer("HuntD");
        final List<Peer> peers = List.of(a,b,c,d);
        final AtomicLong clock = new AtomicLong(System.nanoTime()), searchClock = new AtomicLong(1_000_000_000L);
        final LinkedBlockingQueue<Runnable> held = new LinkedBlockingQueue<>();
        final AtomicReference<RoundSession.Reply> reply = new AtomicReference<>();
        final ServerLevel[] levels = new ServerLevel[3]; final Vec3[] anchor = new Vec3[3];
        RoundSession session; PlayerProfiles profiles; RoundWorldBundle worlds;
        boolean hold; String stage; int refunds, oldModel, faultSortie, stageAt, restoringShows; long started; Object originalAttempt, oldAttempt;
        ServerPlayer oldBody;
        java.util.concurrent.CompletableFuture<Void> ioTask;
        Fixture(GameTestHelper test) { this.test=test; server=test.getLevel().getServer(); }
        void bind() {
            session = service("SESSIONS",RoundSession.class); profiles=service("PROFILES",PlayerProfiles.class);
            set(session,"clock", (MonotonicClock)clock::get);
            set(session,"callbacks", (java.util.concurrent.Executor)held::add);
            @SuppressWarnings("unchecked") var listener=(java.util.function.BiConsumer<RoundSnapshot,RoundSnapshot>)field(session,"committedListener");
            session.onCommitted((before,after) -> {
                listener.accept(before,after);
                for (var changed : after.participants().values()) {
                    var prior = before.participants().get(changed.id());
                    if (prior != null && prior.role().isMonster() && changed.role() == ParticipantRole.HUMAN_ACTIVE) {
                        refresh(); int id = model(d, changed.id().equals(a.id()) ? a : c);
                        test.assertTrue(d.connection.packets.stream().filter(ClientboundSetEntityDataPacket.class::isInstance).map(ClientboundSetEntityDataPacket.class::cast)
                                .filter(data -> data.id()==id).flatMap(data -> data.packedItems().stream())
                                .anyMatch(data -> data.value().toString().contains("复生核验中")), "Committed revival is RESTORING before profile/online admission, never AWAY");
                        restoringShows++;
                    }
                }
                var p=after.participants().get(c.id()); var old=before.participants().get(c.id());
                if(p!=null && old!=null && old.role()==ParticipantRole.MONSTER_DEPLOYED && p.role()==ParticipantRole.MONSTER_LOBBY && p.monsterCooldownNanos()==0) refunds++;
            });
        }
        void tick() {
            clock.accumulateAndGet(System.nanoTime(), Math::max);
            if(!hold) { Runnable task; while((task=held.poll())!=null) task.run(); }
            for(var p:peers) p.ack();
        }
        void await(boolean ok,String text) {
            if(!Objects.equals(stage,text)) { stage=text; stageAt=server.getTickCount(); System.out.println("PERSONAL_HUNT_STAGE: "+text+"; tick="+server.getTickCount()); }
            java.util.concurrent.locks.LockSupport.parkNanos(2_000_000);
            if(!ok && server.getTickCount()-stageAt > 2000) throw new IllegalStateException("Stage timed out: "+text+", searches="+searches().keySet()+", pending="+pending().keySet()+", state="+RoundRuntime.status(server));
            test.assertTrue(ok,text+": "+RoundRuntime.status(server));
        }
        RoundSnapshot round() { return RoundRuntime.snapshot(server).orElseThrow(); }
        boolean idle() { return !session.mutationPending(); }
        void refresh() { LobbyRuntime.tick(server); }
        Object binding() { return ((Map<?,?>)staticField(LobbyRuntime.class,"BINDINGS")).get(server); }
        @SuppressWarnings("unchecked") Map<UUID,Object> searches() { return (Map<UUID,Object>)field(binding(),"searches"); }
        @SuppressWarnings("unchecked") Map<UUID,Object> pending() { return (Map<UUID,Object>)field(binding(),"pending"); }
        int candidates(Object request) {
            var search=(dev.hardrush.hunt.SafeDeployment.Search)field(request,"search");
            return (int)field(request,"spentCandidates")+(search == null ? 0 : search.result().candidates());
        }
        void useSearchClock() { set(binding(),"searchClock",(java.util.function.LongSupplier)searchClock::get); }
        void arenas() {
            worlds=service("WORLDS",RoundWorldBundle.class);
            var kinds=List.of(Level.OVERWORLD,Level.NETHER,Level.END);
            for(int i=0;i<3;i++) {
                var level=levels[i]=worlds.level(kinds.get(i));
                level.getGameRules().set(GameRules.SPAWN_MOBS,false,server);
                var center=new BlockPos(worlds.spawn().getX()+256, i==0?220:80,worlds.spawn().getZ()+256);
                anchor[i]=Vec3.atBottomCenterOf(center);
                var ticket=new TicketType(TicketType.NO_TIMEOUT,TicketType.FLAG_LOADING|TicketType.FLAG_SIMULATION|TicketType.FLAG_KEEP_DIMENSION_ACTIVE);
                level.getChunkSource().addTicketWithRadius(ticket,new ChunkPos(center.getX() >> 4, center.getZ() >> 4),7);
                // Isolated fixture arenas only. Production search never generates chunks or terrain.
                for(int x=-67;x<=67;x++) for(int z=-67;z<=67;z++) {
                    var feet=center.offset(x,0,z); level.setBlock(feet.below(),Blocks.STONE.defaultBlockState(),2);
                    for(int y=0;y<3;y++) level.setBlock(feet.above(y),Blocks.AIR.defaultBlockState(),2);
                    level.setBlock(feet.above(3),Blocks.STONE.defaultBlockState(),2);
                }
            }
        }
        boolean arenasTicking() {
            for(int i=0;i<3;i++) for(int x:new int[]{-67,67}) for(int z:new int[]{-67,67})
                if(!levels[i].isPositionEntityTicking(BlockPos.containing(anchor[i]).offset(x,0,z))) return false;
            return true;
        }
        void move(Peer p,int dimension) { var pos=anchor[dimension]; p.player.teleportTo(levels[dimension],pos.x,pos.y,pos.z,Set.of(),0,0,true); p.ack(); }
        void moveVoid(Peer p,int dimension) { var pos=anchor[dimension].add(1800,80,1800); p.player.teleportTo(levels[dimension],pos.x,pos.y,pos.z,Set.of(),0,0,true); p.ack(); }
        void kill(Peer p) { test.assertTrue(p.player.hurtServer(p.player.level(),p.player.damageSources().genericKill(),Float.MAX_VALUE),"Native lethal damage accepted"); }
        boolean lobby(Peer p) {
            var body=p.current(); return idle() && p.participant().role()==ParticipantRole.MONSTER_LOBBY && body!=null && body.isAlive()
                    && body.level()==LobbyWorld.open(server).holdingLevel() && profiles.isReady(p.id(),round());
        }
        boolean deployed(Peer p,int dimension) {
            return idle() && p.participant().role()==ParticipantRole.MONSTER_DEPLOYED && p.player.level()==levels[dimension]
                    && MonsterRoles.isMonster(p.player) && !PlayerProfiles.isLocked(p.player);
        }
        int model(Peer observer,Peer target) { return LobbyInteractions.get(server).orElseThrow().projections().view(observer.player).orElseThrow()
                .entities().stream().filter(s -> s.target().equals(target.id())).findFirst().orElseThrow().entityId(); }
        void assertModels() {
            test.assertValueEqual(server.getPlayerList().getPlayerCount(),4,"Projection never enters PlayerList");
            var view=LobbyInteractions.get(server).orElseThrow().projections().view(c.player).orElseThrow();
            for(var model:view.entities()) test.assertTrue(c.player.level().getEntity(model.entityId())==null,"Projection never enters world simulation");
            for(var p:List.of(a,b)) {
                int id=model(c,p);
                test.assertTrue(c.connection.packets.stream().filter(ClientboundSetEntityDataPacket.class::isInstance).map(ClientboundSetEntityDataPacket.class::cast)
                        .filter(data -> data.id()==id).flatMap(data -> data.packedItems().stream()).anyMatch(data -> data.value() instanceof net.minecraft.world.item.component.ResolvableProfile profile
                                && profile.partialProfile().equals(p.player.getGameProfile())),"Full original authenticated profile reaches mannequin");
            }
            test.assertTrue(ProtectionRuntime.protectedEntity(c.player),"Model inputs execute with production protection");
        }
        void assertAway() {
            int id=model(c,a);
            test.assertTrue(c.connection.packets.stream().filter(ClientboundSetEntityDataPacket.class::isInstance).map(ClientboundSetEntityDataPacket.class::cast)
                    .filter(data -> data.id()==id).flatMap(data -> data.packedItems().stream()).anyMatch(data -> data.value().toString().contains("暂离")),"Disconnected human retains an away display");
        }
        void commitRace(GameTestSequence s,boolean terrain) {
            var baseline=new int[2]; var found=new Vec3[1];
            s.thenExecute(() -> { move(a,0); useSearchClock(); searchClock.addAndGet(3_000_000_000L); hold=true; baseline[0]=refunds; baseline[1]=c.participant().monsterDeploymentSequence(); c.hunt(a); })
                    .thenWaitUntil(() -> await(pending().containsKey(c.id()) && !held.isEmpty(),"SQLite submitted before "+(terrain?"terrain":"movement")+" race"))
                    .thenExecute(() -> {
                        var attempt=pending().get(c.id()); var search=(dev.hardrush.hunt.SafeDeployment.Search)field(attempt,"search"); found[0]=search.result().position();
                        test.assertTrue(c.player.level()==LobbyWorld.open(server).holdingLevel(),"No teleport before real SQLite callback");
                        if(terrain) levels[0].setBlock(BlockPos.containing(found[0]).below(),Blocks.AIR.defaultBlockState(),2);
                        else { a.player.teleportTo(levels[0],found[0].x,found[0].y,found[0].z,Set.of(),0,0,true); a.ack(); }
                        hold=false;
                    })
                    .thenWaitUntil(() -> await(deployed(c,0),"compensated and revalidated "+(terrain?"terrain":"movement")+" race"))
                    .thenExecute(() -> {
                        test.assertValueEqual(refunds,baseline[0]+1,"Changed condition triggers one durable compensation");
                        test.assertValueEqual(c.participant().monsterDeploymentSequence(),baseline[1]+2,"Compensated sortie is never reused");
                        double distance=c.player.position().subtract(a.player.position()).horizontalDistance();
                        test.assertTrue(distance>=24 && distance<=40,"New result respects current target distance");
                        if(terrain) levels[0].setBlock(BlockPos.containing(found[0]).below(),Blocks.STONE.defaultBlockState(),2);
                        kill(c);
                    }).thenWaitUntil(() -> await(lobby(c),"race defeat back in camp"))
                    .thenExecute(() -> { c.player=c.current(); clock.addAndGet(TimeUnit.SECONDS.toNanos(31)); })
                    .thenWaitUntil(() -> await(idle() && c.participant().monsterCooldownNanos()==0,"race cooldown elapsed"));
        }
        void pages() {
            refresh(); var projections=LobbyInteractions.get(server).orElseThrow().projections();
            var beforeC=projections.view(c.player).orElseThrow(); var beforeD=projections.view(d.player).orElseThrow();
            test.assertValueEqual(beforeC.pages(),2,"Production roster has two pages without a participant cap");
            oldModel=model(c,a);
            HuntInputFixtures.role(c.player,"next"); c.ack();
            var next=projections.view(c.player).orElseThrow();
            test.assertValueEqual(next.page(),1,"Actual protected paging facility changes own page");
            test.assertValueEqual(projections.view(d.player).orElseThrow(),beforeD,"Another observer retains page, generation and IDs");
            HuntInputFixtures.click(c.player,oldModel,InteractionHand.MAIN_HAND);
            test.assertTrue(searches().isEmpty(),"Old page cannot select replacement model");
            HuntInputFixtures.role(c.player,"previous"); c.ack();
            test.assertValueEqual(projections.view(c.player).orElseThrow().page(),0,"Previous facility restores own page");
            test.assertTrue(model(c,a)!=oldModel,"Returning page uses fresh entity IDs");
            test.assertValueEqual(server.getPlayerList().getPlayerCount(),4,"Eighteen offline fixture candidates never count as native connections");
        }
        void prepareFault() {
            if(!System.getProperty(TestProperties.PERSONAL_HUNT_FAULT,"adapter").equals("storage")) { ioTask=java.util.concurrent.CompletableFuture.completedFuture(null); return; }
            var database=server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve(dev.hardrush.storage.RoundStoragePaths.DATABASE);
            ioTask=java.util.concurrent.CompletableFuture.runAsync(() -> {
                try(var connection=java.sql.DriverManager.getConnection("jdbc:sqlite:"+database);var sql=connection.createStatement()) {
                    sql.execute("CREATE TRIGGER fail_personal_hunt BEFORE INSERT ON store_events WHEN NEW.event_type='MONSTER_DEPLOY' BEGIN SELECT RAISE(ABORT,'controlled personal hunt storage failure'); END");
                } catch(java.sql.SQLException error) { throw new IllegalStateException(error); }
            });
        }
        void profileFault() {
            ioTask.join(); faultSortie=c.participant().monsterDeploymentSequence();
            if(System.getProperty(TestProperties.PERSONAL_HUNT_FAULT,"adapter").equals("storage")) c.hunt(a);
            else session.adapterFailure("personal hunt controlled world/profile adapter failure",new IllegalStateException("test adapter fault, not physical media failure"));
        }
        <T>T service(String name,Class<T> type) { return type.cast(((Map<?,?>)staticField(RoundRuntime.class,name)).get(server)); }
        static Object staticField(Class<?> type,String name) { try { var f=type.getDeclaredField(name);f.setAccessible(true);return f.get(null); } catch(ReflectiveOperationException e) {throw new IllegalStateException(e);} }
        static Object field(Object object,String name) { try { var f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object); } catch(ReflectiveOperationException e) {throw new IllegalStateException(e);} }
        static void set(Object object,String name,Object value) { try { var f=object.getClass().getDeclaredField(name);f.setAccessible(true);f.set(object,value); } catch(ReflectiveOperationException e) {throw new IllegalStateException(e);} }
        final class Peer {
            final GameProfile profile; PrepareSpawnTask prepare; Recording connection; EmbeddedChannel channel; ServerPlayer player; int ack=Integer.MIN_VALUE;
            Peer(String name) { profile=new GameProfile(UUID.randomUUID(),name); }
            UUID id() { return profile.id(); } Participant participant() { return round().participants().get(id()); }
            ServerPlayer current() { return server.getPlayerList().getPlayer(id()); }
            void login(GameTestSequence s) {
                s.thenExecute(() -> { prepare=new PrepareSpawnTask(server,new NameAndId(profile));prepare.start(packet -> {}); })
                        .thenWaitUntil(() -> await(prepare.tick(),"native login "+profile.name()))
                        .thenExecute(() -> { connection=new Recording();channel=new EmbeddedChannel(connection);ack=Integer.MIN_VALUE;
                            player=prepare.spawnPlayer(connection,CommonListenerCookie.createInitial(profile,false));prepare.close();ack(); })
                        .thenWaitUntil(() -> await(profiles.isReady(id(),round()),"profile ready "+profile.name()));
            }
            void role(String role) { HuntInputFixtures.role(player,role); ack(); }
            void hunt(Peer target) { HuntInputFixtures.hunt(player,target.id(),connection.packets); ack(); }
            void ack() {
                var body=current(); if(connection==null || body==null) return;
                connection.packets.stream().filter(ClientboundPlayerPositionPacket.class::isInstance).map(ClientboundPlayerPositionPacket.class::cast)
                        .reduce((prev,next) -> next).filter(packet -> packet.id()!=ack).ifPresent(packet -> {
                            ack=packet.id(); body.connection.handleAcceptTeleportPacket(new ServerboundAcceptTeleportationPacket(packet.id(),body.getX(),body.getY(),body.getZ(),body.getYRot(),body.getXRot()));
                            body.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
                        });
            }
            void disconnect() { connection.disconnect(Component.literal("personal hunt reconnect fixture"));connection.handleDisconnection();channel.finishAndReleaseAll();player=null; }
        }
    }
    private static final class Recording extends Connection {
        final ArrayList<Packet<?>> packets=new ArrayList<>(); Recording(){super(PacketFlow.SERVERBOUND);}
        void record(Packet<?> p) {if(p instanceof ClientboundBundlePacket b)b.subPackets().forEach(this::record);else packets.add(p);}
        @Override public void send(Packet<?> p){record(p);}
        @Override public void send(Packet<?> p,ChannelFutureListener l){record(p);}
        @Override public void send(Packet<?> p,ChannelFutureListener l,boolean f){record(p);}
    }
}
