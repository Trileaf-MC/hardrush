package dev.hardrush.gametest;

import com.mojang.authlib.GameProfile;
import dev.hardrush.core.config.GameMode;
import dev.hardrush.core.lobby.LobbyLayout;
import dev.hardrush.core.round.*;
import dev.hardrush.p1.*;
import dev.hardrush.p1.lobby.*;
import dev.hardrush.p1.player.P1PlayerProfiles;
import dev.hardrush.p1.record.P1Library;
import dev.hardrush.p1.world.*;
import dev.hardrush.p1.celebration.P1CelebrationRuntime;
import dev.hardrush.p2.lobby.P2LobbyUi;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.HashedStack;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.*;

/** Twenty native embedded connections; protocol/state checks, never graphical/performance acceptance. */
public final class P1StabilizationGameTest {
    @GameTest(maxTicks=120000)
    public void twentyConnectionsReleaseViewsAndCompleteThreeSpatialRounds(GameTestHelper test) {
        if(!Boolean.getBoolean("hardrush.lobby.testStabilization")){test.succeed();return;}
        var f=new P2RuntimeLobbyGameTest.Fixture(test);
        var scene=new P1FullFlowGameTest.State(f,test);
        var peers=new ArrayList<P2RuntimeLobbyGameTest.Fixture.Peer>();
        peers.add(f.monster);peers.add(f.target);
        for(int i=2;i<20;i++)peers.add(f.new Peer("Stabilize"+i));
        var reply=new AtomicReference<P1LobbySession.Reply>();
        var state=new State(f,scene,peers,test);
        test.onEachTick(()->{
            if(f.clock!=null)f.clock.accumulateAndGet(System.nanoTime(),Math::max);
            for(var peer:peers)if(peer.current()!=null){peer.acknowledge();peer.current().connection.tick();}
            test.assertFalse(P1LobbyRuntime.sessionState(f.server).filter(s->s==P1LobbySession.State.FAILED).isPresent(),"Stabilization failed: "+P1LobbyRuntime.status(f.server));
        });
        var seq=test.startSequence().thenWaitUntil(()->f.await(P1LobbyRuntime.sessionState(f.server).filter(s->s==P1LobbySession.State.OPEN).isPresent(),"Stabilization bootstrap"))
                .thenExecute(f::installClock).thenExecute(()->{
                    try{var field=P1TenRoundGameTest.class.getDeclaredField("accelerated");field.setAccessible(true);field.set(null,f.clock);}
                    catch(ReflectiveOperationException e){throw new IllegalStateException(e);}
                });
        for(var peer:peers)peer.login(seq);
        seq.thenWaitUntil(()->f.await(f.round().phase()==RoundPhase.WAITING&&!f.session.mutationPending(),"Twenty profiles ready"))
                .thenExecute(()->{
                    test.assertValueEqual(f.server.getPlayerList().getPlayers().size(),20,"Twenty actual native PlayerList connections");
                    state.libraryCloseWhileLoading();
                }).thenWaitUntil(()->f.await(state.drain.isDone(),"Closed-page IO drained"))
                .thenIdle(3).thenExecute(state::assertClosedLibrary);
        for(int cycle=0;cycle<8;cycle++)seq.thenExecute(state::projectionAndMenuCycle).thenIdle(2);
        seq.thenExecute(()->{
                    for(var peer:peers)peer.player.connection.handleContainerClose(new ServerboundContainerClosePacket(peer.player.containerMenu.containerId));
                    // Wait real monotonic throttle time; do not weaken reconnect throttling in production.
                    state.throttleDeadline=System.nanoTime()+P2LobbyUi.BUTTON_COOLDOWN_NANOS;
                }).thenWaitUntil(()->f.await(System.nanoTime()>=state.throttleDeadline,"Real throttle expiry")).thenIdle(3).thenExecute(()->state.assertUiReleased(20));
        for(int index=0;index<3;index++){
            int round=index;
            seq.thenExecute(()->{
                        state.oldWorlds=f.service("WORLDS",P1RoundWorldBundle.class);
                        for(var peer:peers)if(peer.current()!=null)scene.assembly(peer.player);
                    })
                    .thenWaitUntil(()->f.await(peers.stream().filter(p->p.current()!=null).allMatch(p->p.participant()!=null&&p.participant().ready())&&!f.session.mutationPending(),"All spatial intentions committed for round "+round))
                    .thenExecute(()->f.clock.addAndGet(TimeUnit.SECONDS.toNanos(30)))
                    .thenWaitUntil(()->f.await(f.round().phase()==RoundPhase.RUNNING&&!f.session.mutationPending()&&peers.stream().filter(p->p.current()!=null).allMatch(p->!P1PlayerProfiles.isLocked(p.player)),"All connected humans admitted "+round))
                    .thenExecute(()->{
                        test.assertValueEqual(f.round().rules().mode(),round==1?GameMode.NORMAL:GameMode.HARDCORE,"Alternating actual rules");
                        if(round==0){
                            var offline=peers.get(19);offline.player.getInventory().setItem(0,new ItemStack(Items.DIAMOND,13));
                            offline.player.getEnderChestInventory().setItem(0,new ItemStack(Items.EMERALD,7));offline.disconnect();
                        }
                    }).thenWaitUntil(()->f.await(!f.session.mutationPending(),"Offline checkpoint drained "+round))
                    .thenExecute(()->{reply.set(null);f.session.updateNextMode(round==0?GameMode.NORMAL:round==1?GameMode.HARDCORE:GameMode.NORMAL,"stabilization operator fixture",reply::set);
                    }).thenWaitUntil(()->f.await(reply.get()!=null&&!f.session.mutationPending(),"Operator next-mode setting persisted "+round));
            seq.thenExecute(()->test.assertTrue(reply.get().success(),"Next mode accepted: "+reply.get().message()));
            if(round==1){
                seq.thenExecute(()->f.monster.player.hurtServer(f.monster.player.level(),f.monster.player.damageSources().genericKill(),Float.MAX_VALUE))
                        .thenWaitUntil(()->f.await(f.monster.participant().role()==ParticipantRole.HUMAN_RESPAWNING&&!f.session.mutationPending(),"Normal native death"))
                        .thenExecute(()->{
                            f.monster.player.connection.handleClientCommand(new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
                            f.monster.player=f.monster.current();f.monster.acknowledge();
                        }).thenWaitUntil(()->f.await(f.monster.participant().lifeSequence()==2&&!P1PlayerProfiles.isLocked(f.monster.player)&&!f.session.mutationPending(),"Normal button creates one life"));
            }
            seq.thenExecute(()->scene.prepareDragon(f.target.player,f.monster.player))
                    .thenWaitUntil(()->f.await(scene.dragon()!=null,"Native first dragon fixture "+round))
                    .thenExecute(()->scene.finishDragon(f.target.player))
                    .thenWaitUntil(()->f.await(P1CelebrationRuntime.active(f.target.player)&&P1CelebrationRuntime.active(f.monster.player)&&!f.session.mutationPending(),"Durable celebration roster "+round))
                    .thenExecute(()->{scene.portal(f.target.player);scene.portal(f.monster.player);})
                    .thenWaitUntil(()->f.await(f.round().sequence()==round+2&&f.round().phase()==RoundPhase.WAITING&&!f.session.mutationPending()
                            &&peers.stream().filter(p->p.current()!=null).allMatch(p->P1PlayerProfiles.canMoveInLobby(p.player)),"Safe next round "+round))
                    .thenExecute(()->state.afterRotation(round+2));
        }
        peers.get(19).login(seq);
        seq.thenExecute(()->{
                    var offline=peers.get(19);test.assertTrue(offline.player.getInventory().isEmpty()&&offline.player.getEnderChestInventory().isEmpty(),"Offline across three rounds never restores old inventory/ender storage");
                    test.assertFalse(P1LobbyWorld.open(f.server).inAssembly(offline.player,false),"Returning UUID must actively assemble");
                    test.assertTrue(offline.participant()==null||offline.participant().lifeSequence()==0,"Old participant life never enters current round");
                    state.libraryCloseWhileLoading();
                    for(var peer:peers)peer.disconnect();
                }).thenWaitUntil(()->f.await(state.drain.isDone(),"Disconnected-page IO drained"))
                .thenExecute(()->state.throttleDeadline=System.nanoTime()+P2LobbyUi.BUTTON_COOLDOWN_NANOS)
                .thenWaitUntil(()->f.await(System.nanoTime()>=state.throttleDeadline,"Disconnected throttle expiry"))
                .thenIdle(6).thenExecute(()->{
                    state.assertUiReleased(0);
                    test.assertValueEqual(f.server.getPlayerList().getPlayers().size(),0,"All native peers removed");
                    scene.clearClock();
                    System.out.println("STABILIZATION_PASS connections=20 rounds=3 modes=hardcore/normal/hardcore cycles=8 offlineAcross=3; synthetic projection targets=41; low-health dragon/test clock; no graphics or performance claim");
                }).thenSucceed();
    }

    private static final class State {
        final P2RuntimeLobbyGameTest.Fixture f;
        final P1FullFlowGameTest.State scene;
        final List<P2RuntimeLobbyGameTest.Fixture.Peer> peers;
        final GameTestHelper test;
        java.util.concurrent.CompletableFuture<?> drain;
        long throttleDeadline;
        P1RoundWorldBundle oldWorlds;
        final Set<Integer> ids=new HashSet<>();
        final List<LobbyProjections.Target> targets=new ArrayList<>();
        final List<Object> closedViews=new ArrayList<>();
        State(P2RuntimeLobbyGameTest.Fixture f,P1FullFlowGameTest.State scene,List<P2RuntimeLobbyGameTest.Fixture.Peer> peers,GameTestHelper test){
            this.f=f;this.scene=scene;this.peers=peers;this.test=test;
            // Isolated projection stress dataset only, not core participants or played lives.
            for(int i=0;i<41;i++)targets.add(new LobbyProjections.Target(new GameProfile(UUID.randomUUID(),"ModelFixture"+i),1,LobbyProjections.State.AVAILABLE));
        }
        Object field(Object owner,String name){try{var field=owner.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(owner);}catch(ReflectiveOperationException e){throw new IllegalStateException(e);}}
        Object service(Class<?> type,String name){try{var field=type.getDeclaredField(name);field.setAccessible(true);return ((Map<?,?>)field.get(null)).get(f.server);}catch(ReflectiveOperationException e){throw new IllegalStateException(e);}}
        Map<?,?> views(){return (Map<?,?>)field(service(P1Library.class,"SERVICES"),"views");}
        void libraryCloseWhileLoading(){
            closedViews.clear();
            for(var peer:peers)if(peer.current()!=null){
                scene.facility(peer.player,LobbyLayout.Facility.HISTORY);
                closedViews.add(views().get(peer.profile.id()));
                peer.player.connection.handleContainerClose(new ServerboundContainerClosePacket(peer.player.containerMenu.containerId));
            }
            drain=f.session.history(1,0);
        }
        void assertClosedLibrary(){
            test.assertValueEqual(views().size(),0,"Closed history pages release owner/selected history");
            for(var v:closedViews){
                @SuppressWarnings("unchecked") var buttons=(List<P2LobbyUi.Button>)field(v,"buttons");
                test.assertValueEqual(buttons.getFirst().label(),"正在查询历史","Late IO cannot update a closed page");
            }
        }
        void projectionAndMenuCycle(){
            var router=LobbyInteractions.get(f.server).orElseThrow();var projections=router.projections();
            for(var peer:peers){
                var page=projections.show(peer.player,f.round().roundId(),targets,0,(p,s)->{throw new AssertionError("Stale model selected a replacement target");});
                for(var selected:page.entities())test.assertTrue(ids.add(selected.entityId()),"Never recycle model IDs across observers/generations");
                var old=page.entities().getFirst();
                var next=projections.turn(peer.player,1).orElseThrow();
                for(var selected:next.entities())test.assertTrue(ids.add(selected.entityId()),"Page replacement allocates new IDs");
                peer.player.connection.handleInteract(new ServerboundInteractPacket(old.entityId(),InteractionHand.MAIN_HAND,null,false));
                test.assertValueEqual(router.lastResult(peer.player).orElseThrow(),LobbyInteractions.Result.UNREGISTERED,"Late entity packet is unregistered");
                scene.facility(peer.player,LobbyLayout.Facility.RULES);
                var m=peer.player.containerMenu;
                peer.player.connection.handleContainerClick(new ServerboundContainerClickPacket(m.containerId,m.getStateId(),(short)0,(byte)0,ContainerInput.PICKUP,new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<>(),HashedStack.EMPTY));
            }
            test.assertValueEqual(((Map<?,?>)field(projections,"pages")).size(),20,"Exactly one page per native observer");
            for(var page:((Map<?,?>)field(projections,"pages")).values())test.assertValueEqual(((Map<?,?>)field(page,"models")).size(),20,"Only current page models retained");
            // Each recording connection receives matching removals; these models never join a native world.
            for(var peer:peers){
                var active=new HashSet<Integer>();
                for(var packet:peer.connection.packets){
                    if(packet instanceof ClientboundAddEntityPacket add&&add.getType()==net.minecraft.world.entity.EntityTypes.MANNEQUIN)active.add(add.getId());
                    if(packet instanceof ClientboundRemoveEntitiesPacket remove)for(int id:remove.entityIds())active.remove(id);
                }
                test.assertValueEqual(active.size(),20,"Wire removals leave exactly one page per observer");
            }
        }
        void assertUiReleased(int online){
            var router=LobbyInteractions.get(f.server).orElseThrow();
            test.assertValueEqual(views().size(),0,"No retained library pages");
            test.assertValueEqual(((Map<?,?>)field(service(P2LobbyUi.class,"BINDINGS"),"clickTimes")).size(),0,"Expired menu throttle UUIDs released");
            test.assertTrue(((Map<?,?>)field(router,"hints")).size()<=online,"Hints retain current connections only");
            test.assertTrue(((Map<?,?>)field(router.projections(),"pages")).size()<=online,"Projection owners bounded by current connections");
            var bars=(net.minecraft.server.level.ServerBossEvent)service(P1PlayerFeedback.class,"BARS");
            if(bars!=null)test.assertValueEqual(bars.getPlayers().size(),online,"Status bar has no stale player bodies");
        }
        void afterRotation(int sequence){
            test.assertValueEqual(oldWorlds.state(),P1RoundWorldBundle.State.CLOSED,"Old world references closed before new round");
            test.assertValueEqual(f.round().sequence(),(long)sequence,"Exactly one new round");
            for(var peer:peers)if(peer.current()!=null){
                peer.player=peer.current();
                test.assertTrue(P1LobbyWorld.isLobby(peer.player.level())&&!P1LobbyWorld.open(f.server).inAssembly(peer.player,false),"New round outside assembly");
                test.assertFalse(peer.player.getInventory().contains(s->s.is(Items.ECHO_SHARD)||s.is(Items.ELYTRA)||s.is(Items.FIREWORK_ROCKET)||s.is(Items.DIAMOND)),"No controls/celebration/old inventory leaks");
                test.assertFalse(P2LobbyUi.isMenu(peer.player),"Old menu closed on rotation");
            }
            test.assertTrue(f.round().participants().values().stream().noneMatch(Participant::ready),"No inherited preparation");
            var router=LobbyInteractions.get(f.server).orElseThrow();
            test.assertValueEqual(((Map<?,?>)field(router.projections(),"pages")).size(),0,"Round clears all entity mappings");
            test.assertValueEqual(views().size(),0,"Round clears selected history references");
            var binding=service(dev.hardrush.p2.lobby.P2LobbyRuntime.class,"BINDINGS");
            for(var name:List.of("slots","deadAt","displays","channels","searches","pending","refunds","retryAt"))test.assertValueEqual(((Map<?,?>)field(binding,name)).size(),0,"Round clears "+name);
            assertUiReleased(19);
        }
    }
}
