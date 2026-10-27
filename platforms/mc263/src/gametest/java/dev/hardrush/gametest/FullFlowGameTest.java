package dev.hardrush.gametest;

import dev.hardrush.core.lobby.LobbyLayout;
import dev.hardrush.core.round.*;
import dev.hardrush.monster.MonsterRoles;
import dev.hardrush.round.*;
import dev.hardrush.world.*;
import dev.hardrush.book.WrittenBookPort;
import dev.hardrush.celebration.*;
import dev.hardrush.lobby.*;
import dev.hardrush.monster.MonsterRoundRuntime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.HashedStack;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.level.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhase;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.dimension.end.EnderDragonFight;
import net.minecraft.world.phys.*;

/** Own isolated JVM; all player decisions enter native position/block/entity/item/container handlers. */
public final class FullFlowGameTest {
    @GameTest(maxTicks=90000)
    public void commandFreeAssemblyHuntReturnAltarRescueCelebrationAndLibrary(GameTestHelper test) {
        if(!Boolean.getBoolean("hardrush.lobby.testFullFlow")){test.succeed();return;}
        var f=new LobbyRuntimeGameTest.Fixture(test);
        var visitor=f.new Peer("LibraryVisitor");
        var state=new State(f,test);
        test.onEachTick(()->{
            if(f.clock!=null)f.clock.accumulateAndGet(System.nanoTime(),Math::max);
            for(var peer:List.of(f.monster,f.target,visitor)){
                peer.acknowledge();
                // Embedded channels are not in ServerConnectionListener's socket list: tick their
                // native listener too, including vanilla post-login invulnerability and item timers.
                if(peer.current()!=null)peer.current().connection.tick();
            }
            test.assertFalse(!state.expectedFailure && RoundRuntime.sessionState(f.server).filter(s->s==RoundSession.State.FAILED).isPresent(),"Full flow failed: "+RoundRuntime.status(f.server));
        });
        var seq=test.startSequence().thenWaitUntil(()->f.await(RoundRuntime.sessionState(f.server).filter(s->s==RoundSession.State.OPEN).isPresent(),"Full-flow bootstrap"))
                .thenExecute(f::installClock).thenExecute(()->{
                    try { var clock=TenRoundGameTest.class.getDeclaredField("accelerated");clock.setAccessible(true);clock.set(null,f.clock); }
                    catch(ReflectiveOperationException e){throw new IllegalStateException(e);}
                });
        f.monster.login(seq); f.target.login(seq); visitor.login(seq);
        seq.thenWaitUntil(()->f.await(f.round().phase()==RoundPhase.WAITING&&!f.session.mutationPending(),"Camp ready"))
                .thenExecute(()->state.assembly(f.monster.player))
                .thenWaitUntil(()->f.await(f.monster.participant()!=null&&f.monster.participant().ready()&&!f.session.mutationPending(),"First spatial readiness"))
                .thenExecute(()->state.assembly(f.target.player))
                .thenWaitUntil(()->f.await(f.target.participant()!=null&&f.target.participant().ready()&&!f.session.mutationPending(),"Second spatial readiness"))
                .thenExecute(()->f.clock.addAndGet(TimeUnit.SECONDS.toNanos(30)))
                .thenWaitUntil(()->f.await(f.humansReady(),"Two admitted human lives"))
                .thenExecute(() -> {
                    f.prepareArena();
                    // This combat fixture runs after the first-sunrise grace period, at night for the zombie body.
                    var daylight = f.level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.WORLD_CLOCK)
                            .getOrThrow(net.minecraft.world.clock.WorldClocks.OVERWORLD);
                    f.level.clockManager().setTotalTicks(daylight, dev.hardrush.core.combat.DawnPact.SUNRISE_TICKS + 18_000);
                })
                .thenWaitUntil(()->f.await(f.arenaTicking(),"Bounded fixture arena ticking"))
                .thenExecute(()->{
                    f.target.move(f.anchor);f.monster.move(f.anchor.add(10,0,0));
                    f.monster.player.getInventory().setItem(0,new ItemStack(Items.DIAMOND,3));
                    f.monster.player.getEnderChestInventory().setItem(0,new ItemStack(Items.EMERALD,6));
                    f.humanDrops=new AABB(f.monster.player.blockPosition()).inflate(5);
                    f.killMonsterPeer(false);
                })
                .thenWaitUntil(()->f.await(f.monsterReady(true),"Hardcore death in camp"))
                .thenExecute(()->{f.monster.player=f.monster.current();HuntInputFixtures.role(f.monster.player,"zombie");})
                .thenWaitUntil(()->f.await(!f.session.mutationPending(),"Role saved"))
                .thenExecute(state::withdrawalShortcut)
                .thenExecute(()->f.clock.addAndGet(TimeUnit.SECONDS.toNanos(30)))
                .thenExecute(()->HuntInputFixtures.hunt(f.monster.player,f.target.profile.id(),f.monster.connection.packets))
                .thenWaitUntil(()->f.await(f.deployed(1),"Model right click safely deployed"))
                .thenExecute(state::withdrawalShortcut)
                .thenIdle(5)
                .thenExecute(()->{
                    state.use(f.monster.player,7);state.use(f.monster.player,7);
                    test.assertTrue(LobbyRuntime.channeling(f.monster.player),"Return item starts once and repeated right click does not interrupt itself");
                }).thenIdle(8)
                .thenExecute(()->{
                    test.assertTrue(f.monster.player.getInventory().getItem(7).is(Items.ECHO_SHARD),"Tick repair preserves fixed return control");
                    test.assertTrue(state.actionBar(f.monster.connection.packets,"正在返回营地"),"Actual progress action bar was emitted; channel="+LobbyRuntime.channeling(f.monster.player)+" feedback="+f.monster.connection.packets.stream().filter(ClientboundSystemChatPacket.class::isInstance).map(ClientboundSystemChatPacket.class::cast).map(p->p.content().getString()).toList());
                    f.monster.move(f.monster.player.position().add(.2,0,0));
                })
                .thenWaitUntil(()->f.await(!LobbyRuntime.channeling(f.monster.player),"Movement cancels return"))
                .thenExecute(()->{
                    state.use(f.monster.player,7); f.monster.player.connection.handlePunch(ServerboundPunchPacket.INSTANCE);
                    test.assertFalse(LobbyRuntime.channeling(f.monster.player),"Punch cancels return");
                    state.use(f.monster.player,7); state.use(f.monster.player,2);
                    test.assertFalse(LobbyRuntime.channeling(f.monster.player),"Ordinary item attempt cancels return");
                    state.use(f.monster.player,7); f.monster.player.setInvulnerableTime(0);
                    f.monster.player.hurtServer(f.level,f.monster.player.damageSources().generic(),1);
                    test.assertFalse(LobbyRuntime.channeling(f.monster.player),"Damage cancels return");
                    state.use(f.monster.player,7); state.use(f.monster.player,1);
                    test.assertFalse(LobbyRuntime.channeling(f.monster.player),"Skill input cancels return before casting");
                })
                .thenWaitUntil(()->f.await(!MonsterRoundRuntime.casting(f.monster.player)&&!f.session.mutationPending(),"Skill durable reservation drained"))
                .thenExecute(f.target::disconnect)
                .thenWaitUntil(()->f.await(!f.target.participant().online()&&!f.session.mutationPending(),"Only human offline commits"))
                .thenExecute(()->{
                    test.assertValueEqual(f.round().phase(),RoundPhase.RUNNING,"Offline human retains eligibility without rescue/wipe");
                    test.assertValueEqual(f.round().pauseReason(),PauseReason.NO_ONLINE_HUMANS,"Only challenge time pauses");
                });
        f.target.login(seq);
        seq.thenWaitUntil(()->f.await(f.target.participant().online()&&!dev.hardrush.player.PlayerProfiles.isLocked(f.target.player)&&!f.session.mutationPending(),"Human reconnect restores same life"))
                .thenExecute(()->{
                    f.monster.move(f.target.player.position().add(1,0,0)); f.target.player.setHealth(1);f.target.player.setInvulnerableTime(0);
                    f.monster.player.connection.handleAttack(new ServerboundAttackPacket(f.target.player.getId()));
                })
                .thenWaitUntil(()->{
                    if(f.target.player.isAlive()){
                        f.monster.player.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
                        f.monster.player.connection.handleAttack(new ServerboundAttackPacket(f.target.player.getId()));
                        if(++state.attacks>200)throw new IllegalStateException("Packet melee stalled: monster="+f.monster.player.position()+" target="+f.target.player.position()+" health="+f.target.player.getHealth()+" canAct="+MonsterRoundRuntime.canAct(f.monster.player)+" clientLoaded="+f.monster.player.connection.hasClientLoaded()+" legal="+MonsterRoles.legalTarget(f.monster.profile.id(),f.level,f.target.player)+" line="+f.monster.player.hasLineOfSight(f.target.player)+" nativeRange="+MonsterRoles.nativeModel(f.monster.player).isWithinMeleeAttackRange(f.target.player)+" targetInvul="+f.target.player.getAbilities().invulnerable+" targetLocked="+dev.hardrush.player.PlayerProfiles.isLocked(f.target.player)+" lookup="+f.level.getEntity(f.target.player.getId())+" body="+MonsterRoles.status(f.monster.player));
                    }
                    f.await(f.round().phase()==RoundPhase.RESCUE&&f.monster.participant().revivalEntitled()&&!f.session.mutationPending(),"Death batch grants entitlement then starts rescue");})
                .thenExecute(()->{
                    test.assertValueEqual(f.session.view().orElseThrow().remainingPhaseMillis(),60000L,"Rescue starts at sixty seconds under controlled clock");
                    state.use(f.monster.player,7);test.assertTrue(LobbyRuntime.channeling(f.monster.player),"Return item works in rescue");
                    f.ageReturnChannel();
                })
                .thenWaitUntil(()->f.await(f.monsterReady(false),"Item return commits lobby and thirty-second cooldown"));
        if (System.getProperty("hardrush.lobby.fullFlowFault","none").equals("revival")) {
            seq.thenExecute(state::revivalFault)
                    .thenWaitUntil(()->f.await(state.io.isDone(),"Real SQLite revival fault installed"))
                    .thenExecute(()->{state.io.join();state.expectedFailure=true;state.facility(f.monster.player,LobbyLayout.Facility.ALTAR);state.click(f.monster.player,"确认复生");})
                    .thenWaitUntil(()->f.await(f.session.state()==RoundSession.State.FAILED,"Revival write rejection enters isolation"))
                    .thenExecute(()->{
                        test.assertTrue(f.monster.participant().revivalEntitled(),"Failed transaction does not consume qualification");
                        test.assertValueEqual(f.monster.participant().lifeSequence(),1,"No new human life published");
                        test.assertValueEqual(f.monster.participant().role(),ParticipantRole.MONSTER_LOBBY,"Monster remains quarantined in camp");
                        System.out.println("FULL_FLOW_REVIVAL_FAULT_PASS qualification/life retained; real SQLite abort; no admission");
                        f.monster.disconnect();f.target.player=f.target.current();f.target.disconnect();visitor.disconnect();state.clearClock();
                    }).thenSucceed();
            return;
        }
        seq.thenExecute(()->{
                    test.assertTrue(f.monster.participant().monsterCooldownNanos()>0,"Return starts sortie cooldown");
                    state.facility(f.monster.player,LobbyLayout.Facility.ALTAR);
                    test.assertTrue(state.has(f.monster.player,"剩余复生次数：1"),"Altar shows the viewer's entitlement");
                    f.monster.player.connection.handleContainerClose(new ServerboundContainerClosePacket(f.monster.player.containerMenu.containerId));
                    test.assertTrue(f.monster.participant().revivalEntitled(),"Closing confirmation consumes nothing");
                    state.facility(f.monster.player,LobbyLayout.Facility.ALTAR);
                    state.click(f.monster.player,"确认复生");state.clickIfPresent(f.monster.player,"确认复生");
                    f.monster.player.connection.handleContainerClose(new ServerboundContainerClosePacket(f.monster.player.containerMenu.containerId));
                })
                .thenWaitUntil(()->f.await(f.round().phase()==RoundPhase.RUNNING&&f.monster.participant().role()==ParticipantRole.HUMAN_ACTIVE&&f.monster.participant().online()
                        && !dev.hardrush.player.PlayerProfiles.isLocked(f.monster.player)&&!f.session.mutationPending(),"Altar restores challenge and one empty life during cooldown"))
                .thenExecute(()->{
                    test.assertValueEqual(f.monster.participant().lifeSequence(),2,"Exactly one qualification was consumed");
                    test.assertTrue(f.monster.player.getInventory().isEmpty(),"Control and return items removed from human inventory");
                    f.target.player=f.target.current();f.monster.move(f.anchor);
                    HuntInputFixtures.role(f.target.player,"creeper");
                })
                .thenWaitUntil(()->f.await(f.target.participant().monsterRole()==MonsterRole.CREEPER&&!f.session.mutationPending(),"Creeper booth saved"))
                .thenExecute(()->{f.clock.addAndGet(TimeUnit.SECONDS.toNanos(30));HuntInputFixtures.hunt(f.target.player,f.monster.profile.id(),f.target.connection.packets);})
                .thenWaitUntil(()->f.await(f.target.participant().role()==ParticipantRole.MONSTER_DEPLOYED&&MonsterRoles.isMonster(f.target.player)&&!f.session.mutationPending(),"Creeper deployed via model"))
                .thenExecute(()->{
                    f.target.move(f.monster.player.position().add(1,0,0)); f.monster.player.setHealth(1); f.monster.player.setInvulnerableTime(0);
                    state.use(f.target.player,0);test.assertTrue(MonsterRoles.isIgniting(f.target.player),"Item ignites native creeper");
                    state.use(f.target.player,7);test.assertFalse(LobbyRuntime.channeling(f.target.player),"Ignited creeper cannot channel return");
                    test.assertTrue(MonsterRoles.isIgniting(f.target.player),"Return cannot cancel the fuse");
                })
                .thenWaitUntil(()->f.await(f.round().phase()==RoundPhase.RESCUE&&f.target.participant().revivalEntitled()
                        && f.target.participant().role()==ParticipantRole.MONSTER_LOBBY&&f.target.current().isAlive()&&!f.session.mutationPending(),"Explosion award and self return precede wipe adjudication"))
                .thenExecute(()->{
                    f.monster.player=f.monster.current();f.target.player=f.target.current();
                    state.facility(visitor.player,LobbyLayout.Facility.MIDROUND);state.click(visitor.player,"加入本轮");
                })
                .thenWaitUntil(()->f.await(f.round().phase()==RoundPhase.RUNNING&&visitor.participant()!=null&&visitor.participant().online()
                        &&!dev.hardrush.player.PlayerProfiles.isLocked(visitor.player)&&!f.session.mutationPending(),"Physical midround gate independently rescues challenge"))
                .thenExecute(()->{state.facility(f.target.player,LobbyLayout.Facility.ALTAR);state.click(f.target.player,"确认复生");})
                .thenWaitUntil(()->f.await(f.target.participant().role()==ParticipantRole.HUMAN_ACTIVE&&f.target.participant().online()
                        &&!dev.hardrush.player.PlayerProfiles.isLocked(f.target.player)&&!f.session.mutationPending(),"Second altar consumes entitlement once"))
                .thenWaitUntil(()->f.await(f.monster.current()!=null&&f.monster.current().isAlive()&&f.profiles.isReady(f.monster.profile.id(),f.round()),"Other dead human replacement verified"))
                .thenExecute(()->{f.monster.player=f.monster.current();f.target.move(f.anchor);f.clock.addAndGet(TimeUnit.SECONDS.toNanos(30));HuntInputFixtures.hunt(f.monster.player,f.target.profile.id(),f.monster.connection.packets);})
                .thenWaitUntil(()->f.await(f.monster.participant().role()==ParticipantRole.MONSTER_DEPLOYED&&MonsterRoles.isMonster(f.monster.player)&&!f.session.mutationPending(),"Monster ready for ceremony roster"))
                .thenExecute(()->state.prepareDragon(f.target.player,f.monster.player))
                .thenWaitUntil(()->f.await(state.dragon()!=null,"Low-health native first dragon fixture"))
                .thenExecute(()->state.finishDragon(f.target.player))
                .thenWaitUntil(()->f.await(CelebrationRuntime.active(f.target.player)&&CelebrationRuntime.active(f.monster.player)&&!f.session.mutationPending(),"End human and monster receive durable ceremony kits"))
                .thenExecute(()->{
                    state.oldRound=f.round().roundId();state.oldWorlds=f.service("WORLDS",RoundWorldBundle.class);
                    test.assertTrue(f.target.player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA),"Human ceremony elytra");
                    test.assertTrue(f.monster.player.getItemBySlot(EquipmentSlot.CHEST).is(Items.ELYTRA),"Monster normal body ceremony elytra");
                    test.assertFalse(MonsterRoles.isMonster(f.monster.player),"Ceremony suspends combat role presentation");
                    state.portal(f.monster.player);
                })
                .thenWaitUntil(()->f.await(LobbyWorld.isLobby(f.monster.player.level())&&f.profiles.isReady(f.monster.profile.id(),f.round()),"Monster uses dragon exit to independent lobby"))
                .thenExecute(()->{test.assertTrue(f.round().roundId().equals(state.oldRound),"Remaining online human prevents early completion");state.portal(f.target.player);})
                .thenWaitUntil(()->f.await(f.round().sequence()==2&&f.round().phase()==RoundPhase.WAITING&&f.profiles.isReady(visitor.profile.id(),f.round())&&!f.session.mutationPending(),"Everyone returned; safe release and one next round"))
                .thenExecute(()->{
                    test.assertValueEqual(state.oldWorlds.state(),RoundWorldBundle.State.CLOSED,"Old native worlds closed before next assembly");
                    test.assertFalse(LobbyWorld.open(f.server).inAssembly(visitor.player,false),"Next round lands outside assembly");
                    test.assertTrue(f.round().participants().values().stream().noneMatch(Participant::ready),"Next round requires a fresh spatial intention");
                    test.assertFalse(visitor.player.getInventory().contains(s->s.is(Items.ECHO_SHARD)||s.is(Items.ELYTRA)||s.is(Items.FIREWORK_ROCKET)),"No controls or ceremony gear cross rounds");
                    state.facility(visitor.player,LobbyLayout.Facility.HISTORY);
                })
                .thenWaitUntil(()->f.await(state.has(visitor.player,"第 1 轮"),"Library loads terminal round"))
                .thenExecute(()->state.click(visitor.player,"第 1 轮"))
                .thenWaitUntil(()->f.await(state.has(visitor.player,"参赛名单"),"History details loaded without UUID input"))
                .thenExecute(()->state.click(visitor.player,"参赛名单"))
                .thenWaitUntil(()->f.await(state.has(visitor.player,"P2LobbyMonster"),"Participants page from shared query service"))
                .thenExecute(()->state.click(visitor.player,"返回本轮详情"))
                .thenWaitUntil(()->f.await(state.has(visitor.player,"选择分卷"),"Book selection ready"))
                .thenExecute(()->state.click(visitor.player,"选择分卷"))
                .thenWaitUntil(()->f.await(state.has(visitor.player,"领取主册"),"Volume catalog loaded"))
                .thenExecute(()->state.click(visitor.player,"领取主册"))
                .thenWaitUntil(()->f.await(visitor.player.getInventory().contains(WrittenBookPort::isHistoryBook),"Actual written history book delivered"))
                .thenExecute(()->{
                    var item=java.util.stream.IntStream.range(0,36).mapToObj(i->visitor.player.getInventory().getItem(i)).filter(WrittenBookPort::isHistoryBook).findFirst().orElseThrow();
                    test.assertFalse(item.get(DataComponents.WRITTEN_BOOK_CONTENT).pages().stream().anyMatch(p->p.raw().getString().contains("/hardrush")),"Book instructions use the library entrance");
                    System.out.println("FULL_FLOW_PASS round="+state.oldRound+" next="+f.round().roundId()+"; real packet decisions, fixture terrain/clock/health only");
                }).thenExecute(state::seedHistory)
                .thenWaitUntil(()->f.await(state.io.isDone(),"Synthetic history pagination dataset committed"))
                .thenExecute(()->{state.io.join();state.facility(visitor.player,LobbyLayout.Facility.HISTORY);})
                .thenWaitUntil(()->f.await(state.has(visitor.player,"下一批轮次"),"More than twenty terminal rounds exposes history next page"))
                .thenExecute(()->state.click(visitor.player,"下一批轮次"))
                .thenWaitUntil(()->f.await(state.has(visitor.player,"上一批轮次"),"History second page is independently reachable"))
                .thenExecute(()->state.facility(visitor.player,LobbyLayout.Facility.HISTORY))
                .thenWaitUntil(()->f.await(state.has(visitor.player,"第 121 轮"),"Synthetic newest terminal record available"))
                .thenExecute(()->state.click(visitor.player,"第 121 轮"))
                .thenWaitUntil(()->f.await(state.has(visitor.player,"参赛名单"),"Synthetic detail"))
                .thenExecute(()->state.click(visitor.player,"参赛名单"))
                .thenWaitUntil(()->f.await(state.has(visitor.player,"下一批名单"),"Forty-two participants have bounded pagination"))
                .thenExecute(()->state.click(visitor.player,"下一批名单"))
                .thenWaitUntil(()->f.await(state.has(visitor.player,"上一批名单"),"Native participant page two"))
                .thenExecute(()->state.click(visitor.player,"返回本轮详情"))
                .thenWaitUntil(()->f.await(state.has(visitor.player,"选择分卷"),"Return to selected round"))
                .thenExecute(()->state.click(visitor.player,"选择分卷"))
                .thenWaitUntil(()->f.await(state.has(visitor.player,"分卷 · 卷 2"),"Second volume is offered"))
                .thenExecute(()->{
                    for(int slot=0;slot<36;slot++)visitor.player.getInventory().setItem(slot,new ItemStack(Items.DIAMOND,64));
                    state.clearBookRate(visitor.profile.id());state.click(visitor.player,"分卷 · 卷 2");
                }).thenIdle(25)
                .thenWaitUntil(()->f.await(visitor.connection.packets.stream().filter(ClientboundSystemChatPacket.class::isInstance).map(ClientboundSystemChatPacket.class::cast).anyMatch(p->p.content().getString().contains("背包已满")),"Full inventory explicitly retains claim opportunity"))
                .thenExecute(()->{
                    state.assertBookClaim(visitor.profile.id(),false);
                    test.assertTrue(java.util.stream.IntStream.range(0,36).allMatch(i->visitor.player.getInventory().getItem(i).is(Items.DIAMOND)&&visitor.player.getInventory().getItem(i).getCount()==64),"Full inventory never overwritten or dropped");
                    visitor.player.getInventory().setItem(35,ItemStack.EMPTY);state.clearBookRate(visitor.profile.id());state.click(visitor.player,"分卷 · 卷 2");
                })
                .thenWaitUntil(()->f.await(WrittenBookPort.isHistoryBook(visitor.player.getInventory().getItem(35)),"Selected second volume succeeds on retry"))
                .thenWaitUntil(()->state.assertBookClaim(visitor.profile.id(),true))
                .thenExecute(()->state.click(visitor.player,"阅读已领取书册"))
                .thenWaitUntil(()->f.await(visitor.connection.packets.stream().anyMatch(ClientboundOpenBookPacket.class::isInstance),"Library reads a book from inventory slot 35 via native open-book packet"))
                .thenExecute(()->{
                    state.facility(visitor.player,LobbyLayout.Facility.HISTORY);
                }).thenWaitUntil(()->f.await(state.has(visitor.player,"普通模式"),"Mode leaderboards available"))
                .thenExecute(()->state.click(visitor.player,"普通模式"))
                .thenWaitUntil(()->f.await(state.has(visitor.player,"暂无已核验"),"Normal board excludes hardcore victory and synthetic aborted rounds"))
                .thenExecute(()->{
                    System.out.println("FULL_FLOW_LIBRARY_PASS 22 synthetic terminal rounds / 42 entrants; history and roster pages; volume2; full inventory; separate normal board");
                    f.monster.disconnect();f.target.disconnect();visitor.disconnect();state.clearClock();
                }).thenSucceed();
    }

    static final class State {
        final LobbyRuntimeGameTest.Fixture f; final GameTestHelper test;
        int attacks; boolean expectedFailure; java.util.concurrent.CompletableFuture<Void> io;
        EnderDragonFight fight; UUID oldRound; RoundWorldBundle oldWorlds;
        State(LobbyRuntimeGameTest.Fixture f,GameTestHelper test){this.f=f;this.test=test;}
        void clearClock(){try{var field=TenRoundGameTest.class.getDeclaredField("accelerated");field.setAccessible(true);field.set(null,null);}catch(ReflectiveOperationException e){throw new IllegalStateException(e);}}
        void clearBookRate(UUID player){
            try{var books=f.service("BOOKS",dev.hardrush.core.book.HistoryBookService.class);var field=books.getClass().getDeclaredField("manualTimes");field.setAccessible(true);((Map<?,?>)field.get(books)).remove(player);}
            catch(ReflectiveOperationException e){throw new IllegalStateException(e);}
        }
        void revivalFault(){
            var db=f.server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("hardrush/p1.sqlite");
            io=java.util.concurrent.CompletableFuture.runAsync(()->{
                try(var connection=java.sql.DriverManager.getConnection("jdbc:sqlite:"+db);var sql=connection.createStatement()){
                    sql.execute("CREATE TRIGGER full_flow_revival_failure BEFORE INSERT ON store_events WHEN NEW.event_type='REVIVAL' BEGIN SELECT RAISE(ABORT,'controlled altar SQLite failure'); END");
                }catch(Exception error){throw new IllegalStateException(error);}
            });
        }
        void assertBookClaim(UUID player,boolean delivered){
            var db=f.server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("hardrush/p1.sqlite");
            try(var connection=java.sql.DriverManager.getConnection("jdbc:sqlite:"+db);var statement=connection.prepareStatement(
                    "SELECT delivery.volume,delivery.delivered FROM book_deliveries delivery JOIN history_rounds history USING(round_id) WHERE history.round_sequence=121 AND delivery.player_id=? AND delivery.volume=2")){
                statement.setString(1,player.toString());
                try(var rows=statement.executeQuery()){
                    test.assertTrue(rows.next(),"The selected volume has a durable pending ledger row");
                    test.assertValueEqual(rows.getInt(1),2,"Persisted pending key is the selected volume");
                    test.assertValueEqual(rows.getBoolean(2),delivered,"Actual SQLite pending/ACK matches the inventory outcome");
                }
            }catch(java.sql.SQLException error){throw new IllegalStateException(error);}
        }
        void seedHistory(){
            var db=f.server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("hardrush/p1.sqlite");
            var rules=f.round().rules();
            io=java.util.concurrent.CompletableFuture.runAsync(()->{
                try(var store=new dev.hardrush.persistence.SqliteRoundStore(db)){
                    for(int index=0;index<22;index++){
                        var id=UUID.randomUUID();var participants=new LinkedHashMap<UUID,Participant>();
                        for(int n=0;n<42;n++){var p=new Participant(UUID.randomUUID(),"PageFixture"+n,ParticipantRole.HUMAN_ACTIVE,false,false,0,0,1,false);participants.put(p.id(),p);}
                        var start=new RoundSnapshot(id,100+index,42,0,rules.withMode(index%2==0?dev.hardrush.core.config.GameMode.NORMAL:dev.hardrush.core.config.GameMode.HARDCORE),RoundPhase.RUNNING,PauseReason.NO_ONLINE_HUMANS,0,0,participants,false,false,null);
                        store.commit(new dev.hardrush.persistence.SnapshotWrite(id,-1,0,RoundSnapshotCodec.encode(start),UUID.randomUUID(),"ROUND_CREATED"));
                        var ended=new RoundSnapshot(id,start.sequence(),42,1,start.rules(),RoundPhase.SETTLING,PauseReason.SETTLED,0,0,participants,false,false,new RoundResult(RoundResult.Kind.ABORTED,null,"synthetic pagination fixture; not a played round"));
                        store.commit(new dev.hardrush.persistence.SnapshotWrite(id,0,1,RoundSnapshotCodec.encode(ended),UUID.randomUUID(),"FINISH"));
                    }
                }catch(Exception error){throw new IllegalStateException(error);}
            });
        }
        void assembly(ServerPlayer p){var l=LobbyWorld.open(f.server);move(p,l.holdingLevel(),l.landing(LobbyLayout.Landing.RESTORE_READY));}
        static void move(ServerPlayer p,ServerLevel level,Vec3 point){p.teleportTo(level,point.x,point.y,point.z,Set.of(),0,0,true);}
        void withdrawalShortcut() {
            var p = f.monster.player;
            var participant = f.monster.participant();
            var main = p.getMainHandItem().copy(); var offhand = p.getOffhandItem().copy();
            ParticipationExitGameTest.shortcut(p, true);
            test.assertTrue(LobbyMenus.isMenu(p), "Lobby and deployed monsters can open withdrawal confirmation through fixed inventory locks");
            p.connection.handleContainerClose(new ServerboundContainerClosePacket(p.containerMenu.containerId));
            ParticipationExitGameTest.sneak(p, false);
            test.assertTrue(ItemStack.matches(main, p.getMainHandItem()) && ItemStack.matches(offhand, p.getOffhandItem()), "Shortcut preserves monster loadout");
            test.assertTrue(f.monster.participant().equals(participant) && !f.session.mutationPending(), "Opening and closing never commits a monster exit");
        }
        void use(ServerPlayer p,int slot){p.connection.handleSetCarriedItem(new ServerboundSetCarriedItemPacket(slot));p.connection.handleUseItem(new ServerboundUseItemPacket(InteractionHand.MAIN_HAND,1,0,0));}
        void facility(ServerPlayer p,LobbyLayout.Facility facility){var l=LobbyWorld.open(f.server);var pos=BlockPos.containing(l.layoutPosition(LobbyLayout.facility(facility)));move(p,l.holdingLevel(),Vec3.atBottomCenterOf(pos.south(2)));p.connection.handleUseItemOn(new ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND,new BlockHitResult(Vec3.atCenterOf(pos),Direction.UP,pos,false),2));test.assertTrue(LobbyMenus.isMenu(p),"Physical facility opened "+facility);}
        boolean has(ServerPlayer p,String label){for(int n=0;n<45&&n<p.containerMenu.slots.size();n++)if(p.containerMenu.getSlot(n).getItem().getHoverName().getString().contains(label))return true;return false;}
        void click(ServerPlayer p,String label){java.util.concurrent.locks.LockSupport.parkNanos(LobbyMenus.BUTTON_COOLDOWN_NANOS+1_000_000);test.assertTrue(clickIfPresent(p,label),"Expected native menu action: "+label);}
        boolean clickIfPresent(ServerPlayer p,String label){for(short n=0;n<45&&n<p.containerMenu.slots.size();n++)if(p.containerMenu.getSlot(n).getItem().getHoverName().getString().contains(label)){var m=p.containerMenu;p.connection.handleContainerClick(new ServerboundContainerClickPacket(m.containerId,m.getStateId(),n,(byte)0,ContainerInput.PICKUP,new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<>(),HashedStack.EMPTY));return true;}return false;}
        boolean actionBar(List<net.minecraft.network.protocol.Packet<?>> packets,String text){return packets.stream().filter(ClientboundSetActionBarTextPacket.class::isInstance).map(ClientboundSetActionBarTextPacket.class::cast).anyMatch(p->p.text().getString().contains(text));}
        void prepareDragon(ServerPlayer human,ServerPlayer monster){var worlds=f.service("WORLDS",RoundWorldBundle.class);var end=worlds.level(Level.END);move(human,end,new Vec3(.5,128,.5));move(monster,end,new Vec3(3.5,128,.5));fight=new EnderDragonFight(false,false,false,Optional.empty(),0,Optional.empty(),Optional.of(new BlockPos(0,64,0)),List.of(),List.of());fight.init(end,end.getSeed(),BlockPos.ZERO);fight.skipArenaLoadedCheck();end.setDragonFight(fight);end.getChunkSource().addTicketWithRadius(TicketType.PORTAL,new ChunkPos(0,0),3);}
        EnderDragon dragon(){var end=f.service("WORLDS",RoundWorldBundle.class).level(Level.END);return fight.dragonUUID()!=null&&end.getEntity(fight.dragonUUID()) instanceof EnderDragon d?d:null;}
        void finishDragon(ServerPlayer p){var d=dragon();d.snapTo(.5,68,.5,0,0);d.setNoAi(true);d.getPhaseManager().setPhase(EnderDragonPhase.SITTING_SCANNING);d.setHealth(.1f);move(p,(ServerLevel)d.level(),d.head.position().add(0,0,1));p.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(Items.DIAMOND_SWORD));p.connection.handleAttack(new ServerboundAttackPacket(d.head.getId()));}
        void portal(ServerPlayer p){var dest=((net.minecraft.world.level.block.EndPortalBlock)Blocks.END_PORTAL).getPortalDestination(p.level(),p,p.blockPosition());test.assertTrue(dest==null,"Native dragon portal intercepted to durable lobby return");}
    }
}
