package dev.hardrush.gametest;

import dev.hardrush.core.lobby.plan.LobbyFunction;
import dev.hardrush.core.round.Bounty;
import dev.hardrush.core.round.MonsterCoins;
import dev.hardrush.core.round.MonsterRole;
import dev.hardrush.core.round.RoundPhase;
import dev.hardrush.lobby.LobbyInteractions;
import dev.hardrush.lobby.LobbyRuntime;
import dev.hardrush.player.PlayerProfiles;
import dev.hardrush.round.RoundRuntime;
import dev.hardrush.round.RoundSession;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;

/** Isolated real-round test using client packets and the same native admission path as LobbyRuntimeGameTest. */
public final class CreeperUnlockGameTest {
    @GameTest(maxTicks = 60000)
    public void paidBoothChangesPerViewerAndTicketSurvivesOnlyVoluntaryReturn(GameTestHelper test) {
        if (!Boolean.getBoolean(TestProperties.CREEPER_UNLOCK)) { test.succeed(); return; }
        var f = new LobbyRuntimeGameTest.Fixture(test);
        var novice = f.new Peer("CreeperNovice");
        var peers = List.of(f.monster, f.target, novice);
        TestTeardown.atEnd(test, () -> {
            peers.forEach(peer -> peer.client.close());
            f.releaseArena();
        });
        test.onEachTick(() -> {
            if (f.clock != null) f.clock.accumulateAndGet(System.nanoTime(), Math::max);
            peers.forEach(LobbyRuntimeGameTest.Fixture.Peer::acknowledge);
        });
        var credit = new AtomicReference<RoundSession.Reply>();
        int[] lockedId = new int[1];
        String[] glowTeam = new String[1];
        var sequence = test.startSequence().thenWaitUntil(() -> f.await(RoundRuntime.sessionState(f.server)
                .filter(state -> state == RoundSession.State.OPEN).isPresent(), "Paid creeper bootstrap"))
                .thenExecute(f::installClock);
        for (var peer : peers) peer.login(sequence);
        sequence.thenWaitUntil(() -> f.await(f.round().phase() == RoundPhase.WAITING && !f.session.mutationPending(), "Camp ready"))
                .thenExecute(() -> test.assertTrue(MonsterCoins.creeperPaid(f.round().rules()), "Fresh hardcore rules charge for creepers"));
        for (var peer : peers) {
            sequence.thenExecute(() -> peer.command("hardrush join"))
                    .thenWaitUntil(() -> f.await(peer.participant() != null && !f.session.mutationPending(), "Joined " + peer.profile.name()));
        }
        for (var peer : peers) {
            sequence.thenExecute(() -> peer.command("hardrush ready"))
                    .thenWaitUntil(() -> f.await(peer.participant().ready() && !f.session.mutationPending(), "Ready " + peer.profile.name()));
        }
        sequence.thenExecute(() -> {
                    booth(f, f.monster, EntityTypes.INTERACTION);
                    f.clock.addAndGet(TimeUnit.SECONDS.toNanos(31));
                })
                .thenWaitUntil(() -> f.await(f.humansReady() && !PlayerProfiles.isLocked(novice.player), "Three humans land"))
                .thenExecute(f::prepareArena)
                .thenWaitUntil(() -> f.await(f.arenaTicking(), "Arena loaded for the native search"))
                .thenExecute(() -> {
                    f.target.move(f.anchor);
                    novice.move(f.anchor.add(8, 0, 0));
                    f.monster.move(f.anchor.add(16, 0, 0));
                    f.dead = f.monster.player;
                    test.assertTrue(f.dead.hurtServer(f.level, f.dead.damageSources().genericKill(), Float.MAX_VALUE),
                            "First native death starts a monster life");
                })
                .thenWaitUntil(() -> f.await(f.monsterReady(true) && CampTransferFixtures.actionsReady(f.session),
                        "First death enters monster camp"))
                .thenExecute(() -> {
                    f.monster.player = f.monster.current();
                    lockedId[0] = booth(f, f.monster, EntityTypes.INTERACTION).getId();
                    HuntInputFixtures.role(f.monster.player, "creeper");
                    test.assertFalse(f.session.mutationPending(), "Insufficient coins are refused before a role write");
                    test.assertTrue(hint(f).contains("解锁苦力怕需要 40 怪物币，现有 " + f.monster.participant().wallet().coins()),
                            "The coin refusal shows the exact price and balance");
                    var p = f.monster.participant();
                    f.session.requestMonsterCoinPickup(f.round().roundId(), p.id(), p.lifeSequence(), p.monsterDeploymentSequence(),
                            40, credit::set);
                })
                .thenWaitUntil(() -> f.await(credit.get() != null && !f.session.mutationPending(), "Fixture coins credited"))
                .thenExecute(() -> {
                    test.assertTrue(credit.get().success(), "The real wallet received the setup coins");
                    HuntInputFixtures.role(f.monster.player, "creeper");
                    test.assertFalse(f.session.mutationPending(), "No three-star quarry is refused before a role write");
                    test.assertTrue(hint(f).contains("场上还没有三星以上的人类"), "The refusal names the missing quarry");
                    for (String advancement : List.of("minecraft:story/mine_diamond", "minecraft:story/enter_the_nether",
                            "minecraft:nether/find_fortress", "minecraft:nether/obtain_blaze_rod")) {
                        var quarry = f.target.participant();
                        f.session.threatMilestone(f.round().roundId(), quarry.id(), quarry.lifeSequence(), advancement);
                    }
                    f.clock.addAndGet(TimeUnit.SECONDS.toNanos(45));
                })
                .thenWaitUntil(() -> f.await(CampTransferFixtures.actionsReady(f.session) && f.monster.participant().monsterCooldownNanos() == 0
                        && Bounty.standing(f.round().rules(), f.round().participants(), f.target.profile.id()).stars() == 3,
                        "A committed three-star quarry and expired deploy wait"))
                .thenExecute(() -> {
                    test.assertValueEqual(Bounty.standing(f.round().rules(), f.round().participants(), novice.profile.id()).stars(),
                            1, "The second model is a one-star human");
                    test.assertValueEqual(MonsterCoins.creeperUnlockPrice(f.round().rules()), 40, "Default price");
                    HuntInputFixtures.role(f.monster.player, "creeper");
                })
                .thenWaitUntil(() -> f.await(f.monster.participant().wallet().creeperTicket() && !f.session.mutationPending(), "Native booth click buys a ticket"))
                .thenExecute(() -> {
                    var p = f.monster.participant();
                    test.assertValueEqual(p.monsterRole(), MonsterRole.CREEPER, "Payment also selects the creeper role");
                    test.assertValueEqual(p.wallet().coins(), MonsterCoins.amount(f.round().rules(), MonsterCoins.STARTER),
                            "Exactly forty coins were debited");
                    var spawn = booth(f, f.monster, EntityTypes.CREEPER);
                    test.assertTrue(spawn.getId() != lockedId[0] && removed(f, lockedId[0]), "The click ID moved from the removed Interaction to the creeper");
                    var team = f.monster.client.packets(ClientboundSetPlayerTeamPacket.class).stream()
                            .filter(packet -> packet.getTeamAction() == ClientboundSetPlayerTeamPacket.Action.ADD
                                    && packet.getPlayers().contains(spawn.getUUID().toString()))
                            .findFirst().orElseThrow();
                    glowTeam[0] = team.getName();
                    test.assertFalse(novice.client.packets(ClientboundSetPlayerTeamPacket.class).stream()
                            .anyMatch(packet -> packet.getName().equals(team.getName())), "The other connection never gets this glow team");

                    f.monster.hunt(novice.profile.id());
                    assertRejected(f, "苦力怕只追三星以上的人类");
                    test.assertTrue(f.monster.participant().wallet().creeperTicket(), "A one-star rejection keeps the ticket");
                    f.monster.hunt(f.target.profile.id());
                })
                .thenWaitUntil(() -> f.await(f.deployed(1), "Paid creeper hunts the three-star human"))
                .thenExecute(() -> {
                    test.assertTrue(f.monster.participant().wallet().creeperTicket(), "Landing keeps the ticket until the sortie ends");
                    LobbyInteractions.tick(f.server);
                    test.assertTrue(f.monster.client.packets(ClientboundSetPlayerTeamPacket.class).stream()
                            .anyMatch(packet -> packet.getName().equals(glowTeam[0])
                                    && packet.getTeamAction() == ClientboundSetPlayerTeamPacket.Action.REMOVE),
                            "Leaving the dimension explicitly removes the glow team on the original connection");
                    test.assertTrue(LobbyRuntime.useReturn(f.monster.player), "The creeper can channel a voluntary return");
                    f.ageReturnChannel();
                })
                .thenWaitUntil(() -> f.await(f.monsterReady(false), "Voluntary return lands in camp"))
                .thenExecute(() -> {
                    test.assertTrue(f.monster.participant().wallet().creeperTicket(), "Voluntary return keeps the same ticket");
                    booth(f, f.monster, EntityTypes.CREEPER);
                    f.monster.hunt(f.target.profile.id());
                })
                .thenWaitUntil(() -> f.await(f.deployed(2), "The same ticket resumes a second outing without payment"))
                .thenExecute(() -> {
                    f.dead = f.monster.player;
                    test.assertTrue(f.dead.hurtServer(f.level, f.dead.damageSources().genericKill(), Float.MAX_VALUE),
                            "The active creeper body dies in the challenge world");
                })
                .thenWaitUntil(() -> f.await(f.monsterReady(true), "Defeat returns a replacement to monster camp"))
                .thenExecute(() -> {
                    f.monster.player = f.monster.current();
                    test.assertFalse(f.monster.participant().wallet().creeperTicket(), "Defeat spends the ticket");
                    test.assertValueEqual(f.monster.participant().monsterRole(), MonsterRole.CREEPER, "Defeat keeps the selected role");
                    booth(f, f.monster, EntityTypes.INTERACTION);
                })
                .thenWaitUntil(() -> f.await(hint(f).contains("苦力怕需重新解锁"), "Respawned creeper is told it must unlock again"))
                .thenExecute(() -> {
                    f.monster.hunt(f.target.profile.id());
                    assertRejected(f, "回苦力怕展位付 40 怪物币解锁");
                    test.assertValueEqual(f.monster.participant().monsterDeploymentSequence(), 2, "The locked role cannot reserve a third sortie");
                }).thenSucceed();
    }

    private static ClientboundAddEntityPacket booth(LobbyRuntimeGameTest.Fixture f, LobbyRuntimeGameTest.Fixture.Peer viewer,
                                                    EntityType<?> expected) {
        LobbyInteractions.tick(f.server);
        int id = LobbyInteractions.get(f.server).orElseThrow().roleModels().view(viewer.player).get(LobbyFunction.ROLE_CREEPER).getFirst();
        var spawn = viewer.client.packets(ClientboundAddEntityPacket.class).stream().filter(packet -> packet.getId() == id)
                .findFirst().orElseThrow();
        f.test.assertTrue(spawn.getType() == expected, "The booth's clickable ID points to " + expected + ", got " + spawn.getType());
        return spawn;
    }

    private static boolean removed(LobbyRuntimeGameTest.Fixture f, int entity) {
        return f.monster.client.packets(ClientboundRemoveEntitiesPacket.class).stream().anyMatch(packet -> packet.entityIds().contains(entity));
    }

    private static String hint(LobbyRuntimeGameTest.Fixture f) {
        return LobbyInteractions.get(f.server).orElseThrow().hints(f.monster.player)
                .current(System.nanoTime() / 1_000_000).orElse("");
    }

    private static void assertRejected(LobbyRuntimeGameTest.Fixture f, String wording) {
        f.test.assertTrue(hint(f).contains(wording), "Expected the hunt refusal: " + wording);
        f.test.assertTrue(LobbyRuntime.huntTarget(f.monster.player).isEmpty(), "The rejected click never starts searching");
    }
}
