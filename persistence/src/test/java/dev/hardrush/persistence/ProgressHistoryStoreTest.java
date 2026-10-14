package dev.hardrush.persistence;

import static org.junit.jupiter.api.Assertions.*;
import dev.hardrush.core.api.DamagePort.AttackKind;
import dev.hardrush.core.combat.*;
import dev.hardrush.core.config.*;
import dev.hardrush.core.record.*;
import dev.hardrush.core.round.*;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProgressHistoryStoreTest {
    @TempDir Path directory;
    private final UUID player = UUID.randomUUID();
    private final String setA = "a".repeat(64), setB = "b".repeat(64);
    private Path database() throws Exception { return directory.toRealPath().resolve("records.sqlite"); }

    @Test void progressUnionsIdsAndRetainsFirstEndAndEverySetAcrossReopenAndRoundIsolation() throws Exception {
        Path db = database(); UUID round = UUID.randomUUID(), next = UUID.randomUUID();
        var first = progress(round, UUID.randomUUID(), Set.of("minecraft:story/mine_stone"), setA, true, 100);
        try (var store = new SqliteRoundStore(db)) {
            create(store, round, 1, RulesConfig.defaults(), false);
            assertEquals(CommitResult.APPLIED, store.recordProgress(first));
            assertEquals(CommitResult.DUPLICATE, store.recordProgress(first));
            assertEquals(CommitResult.APPLIED, store.recordProgress(progress(round, UUID.randomUUID(),
                    Set.of("minecraft:story/mine_stone", "minecraft:story/upgrade_tools"), setB, true, 900)));
            var record = store.participantRecord(round, player).orElseThrow();
            assertEquals(2, record.advancementCount()); assertEquals(100, record.firstEndNanos());
            assertEquals(Set.of(setA,setB), record.advancementSetFingerprints()); assertEquals(1, store.endEntrantCount(round));
            create(store, next, 2, RulesConfig.defaults(), false);
            assertEquals(0, store.participantRecord(next, player).orElseThrow().advancementCount());
            assertEquals(0, store.endEntrantCount(next));
        }
        try (var store = new SqliteRoundStore(db)) {
            assertEquals(CommitResult.DUPLICATE, store.recordProgress(first));
            assertEquals(2, store.participantRecord(round, player).orElseThrow().advancementCount());
            assertEquals(List.of("minecraft:story/mine_stone", "minecraft:story/upgrade_tools"), store.advancementIds(round,player,10,0));
        }
    }

    @Test void admissionNameAndTimeAreFrozenAndNormalDeathRespawnAreCountedOnce() throws Exception {
        try (var store = new SqliteRoundStore(database())) {
            UUID round = UUID.randomUUID(); create(store, round, 1, RulesConfig.defaults().withMode(GameMode.NORMAL), false);
            var before = RoundSnapshotCodec.decode(store.load(round).orElseThrow().payload());
            var deadPlayer = new Participant(player,"Renamed",ParticipantRole.HUMAN_RESPAWNING,true,false,300,0,1,false);
            var dead = snapshot(before,1,RoundPhase.RUNNING,Map.of(player,deadPlayer),null,false,300);
            var write = new SnapshotWrite(round,0,1,RoundSnapshotCodec.encode(dead),UUID.randomUUID(),"NORMAL_DEATH");
            assertEquals(CommitResult.APPLIED,store.commit(write)); assertEquals(CommitResult.DUPLICATE,store.commit(write));
            var respawned = new Participant(player,"RenamedAgain",ParticipantRole.HUMAN_ACTIVE,true,false,300,0,2,false);
            store.commit(new SnapshotWrite(round,1,2,RoundSnapshotCodec.encode(snapshot(dead,2,RoundPhase.RUNNING,Map.of(player,respawned),null,false,300)),UUID.randomUUID(),"NORMAL_RESPAWN"));
            var record = store.participantRecord(round,player).orElseThrow();
            assertEquals("Alice",record.playerName()); assertEquals(0,record.joinedAtChallengeNanos());
            assertEquals(1,record.deathCount()); assertEquals(1,record.revivalCount()); assertEquals(300,record.humanNanos());
        }
    }

    @Test void collisionAcrossProgressSnapshotAndEvidenceNeverLeavesPartialRows() throws Exception {
        try (var store = new SqliteRoundStore(database())) {
            UUID round = UUID.randomUUID(), event = UUID.randomUUID(); create(store,round,1,RulesConfig.defaults(),false);
            var first = progress(round,event,Set.of(),setA,false,100);
            assertEquals(CommitResult.APPLIED,store.recordProgress(first));
            assertThrows(EventCollisionException.class,()->store.recordProgress(progress(round,event,Set.of(),setB,false,100)));
            assertThrows(EventCollisionException.class,()->store.recordEvidence(new EvidenceWrite(round,event,"COMBAT",new byte[]{1})));
            assertThrows(EventCollisionException.class,()->store.commit(new SnapshotWrite(round,0,1,new byte[]{1},event,"CHECKPOINT")));
            assertEquals(0,store.load(round).orElseThrow().version()); assertEquals(0,store.participantRecord(round,player).orElseThrow().advancementCount());
        }
    }

    @Test void progressFailureRollsBackAllFactsAndIdenticalRetrySucceeds() throws Exception {
        Path db = database(); UUID round=UUID.randomUUID(); var write=progress(round,UUID.randomUUID(),Set.of("minecraft:story/mine_stone"),setA,true,100);
        try(var store=new SqliteRoundStore(db);var raw=DriverManager.getConnection("jdbc:sqlite:"+db);var sql=raw.createStatement()) {
            create(store,round,1,RulesConfig.defaults(),false);
            sql.execute("CREATE TRIGGER fail_progress BEFORE UPDATE ON participant_records BEGIN SELECT RAISE(ABORT,'injected'); END");
            assertThrows(SQLException.class,()->store.recordProgress(write));
            assertEquals(0,store.endEntrantCount(round)); assertTrue(store.advancementIds(round,player,10,0).isEmpty());
            sql.execute("DROP TRIGGER fail_progress"); assertEquals(CommitResult.APPLIED,store.recordProgress(write));
        }
    }

    @Test void leaderboardUsesFullRulesFingerprintAndMillisecondCompetitionRanks() throws Exception {
        try(var store=new SqliteRoundStore(database())) {
            var rules=RulesConfig.defaults(); UUID a=UUID.randomUUID(),b=UUID.randomUUID(),c=UUID.randomUUID(),other=UUID.randomUUID();
            create(store,a,1,rules,false); finish(store,a,10_000_001,true);
            create(store,b,2,rules,false); finish(store,b,10_999_999,true);
            create(store,c,3,rules,false); finish(store,c,11_000_000,true);
            var changed=new RulesConfig(rules.schemaVersion(),rules.mode(),rules.minecraftVersion(),rules.difficulty(),rules.generationRule(),rules.combatValues(),rules.protectionRadius()+1,rules.startCountdownMillis(),rules.successCelebrationMillis(),rules.failureDisplayMillis());
            create(store,other,4,changed,false);finish(store,other,1_000_000,true);
            var board=store.leaderboard(rules.fingerprint(),10);
            assertEquals(List.of(1,1,3),board.stream().map(LeaderboardEntry::rank).toList());
            assertEquals(List.of(a,b,c),board.stream().map(e->e.round().roundId()).toList());
            assertEquals(1,store.leaderboard(changed.fingerprint(),10).size());
        }
    }

    @Test void uncertainUnknownFailedAbortedAndLegacyCompletionRemainHistoryButNeverMixIntoBoard() throws Exception {
        try(var store=new SqliteRoundStore(database())) {
            var rules=RulesConfig.defaults(); UUID uncertain=UUID.randomUUID(),unknown=UUID.randomUUID();
            create(store,uncertain,1,rules,true);finish(store,uncertain,1_000_000,true);
            create(store,unknown,2,rules,false);finish(store,unknown,1_000_000,false);
            long sequence=3;
            for(var result:List.of(new RoundResult(RoundResult.Kind.FAILED,null,"failure"),new RoundResult(RoundResult.Kind.ABORTED,null,"admin reason"),new RoundResult(RoundResult.Kind.COMPLETED,player,"legacy without evidence"))) {
                UUID round=UUID.randomUUID();create(store,round,sequence++,rules,false);
                var before=RoundSnapshotCodec.decode(store.load(round).orElseThrow().payload());
                var after=snapshot(before,1,RoundPhase.SETTLING,before.participants(),result,false,1_000_000);
                store.commit(new SnapshotWrite(round,0,1,RoundSnapshotCodec.encode(after),UUID.randomUUID(),"FINISH"));
            }
            assertEquals(5,store.history(10,0).size());assertTrue(store.leaderboard(rules.fingerprint(),10).isEmpty());
            assertEquals(4,store.latestHistoryBefore(5).orElseThrow().sequence());assertEquals(3,store.historyBySequence(3).orElseThrow().sequence());
        }
    }

    @Test void terminalFactsFreezeDespiteLaterCheckpointRenameAndProgressIsRejected() throws Exception {
        try(var store=new SqliteRoundStore(database())) {
            UUID round=UUID.randomUUID();create(store,round,1,RulesConfig.defaults(),false);
            store.recordProgress(progress(round,UUID.randomUUID(),Set.of("minecraft:story/mine_stone"),setA,true,100));
            finish(store,round,10_000_000,true);
            var before=RoundSnapshotCodec.decode(store.load(round).orElseThrow().payload());
            var changed=new Participant(player,"Changed",ParticipantRole.HUMAN_ACTIVE,false,false,999,0,2,false);
            store.commit(new SnapshotWrite(round,1,2,RoundSnapshotCodec.encode(snapshot(before,2,RoundPhase.CELEBRATING,Map.of(player,changed),before.result(),false,before.challengeNanos())),UUID.randomUUID(),"CHECKPOINT"));
            var record=store.participantRecord(round,player).orElseThrow();
            assertEquals("Alice",record.playerName());assertEquals(100,record.humanNanos());assertEquals(0,record.revivalCount());assertEquals(1,record.advancementCount());
            assertEquals(CommitResult.STALE,store.recordProgress(progress(round,UUID.randomUUID(),Set.of("minecraft:story/upgrade_tools"),setB,true,11_000_000)));
            assertEquals(Set.of(setA),store.roundHistory(round).orElseThrow().advancementSetFingerprints());
        }
    }

    @Test void bookClaimsPersistLatestOnlyAndDeliveredClaimNeverBecomesPendingAgain() throws Exception {
        Path db=database();UUID older=UUID.randomUUID(),latest=UUID.randomUUID();
        try(var store=new SqliteRoundStore(db)) {
            create(store,older,1,RulesConfig.defaults(),false);finish(store,older,1_000_000,true);
            create(store,latest,2,RulesConfig.defaults(),false);finish(store,latest,1_000_000,true);
            store.ensureAutomaticBookClaim(player,older,1,1);store.ensureAutomaticBookClaim(player,latest,1,1);
            assertEquals(latest,store.latestAutomaticBookClaim(player).orElseThrow().roundId());
            assertTrue(store.markBookDelivered(player,latest,1,1));assertTrue(store.markBookDelivered(player,latest,1,1));
            assertTrue(store.ensureAutomaticBookClaim(player,latest,1,1).delivered());
            assertThrows(IllegalArgumentException.class,()->store.ensureAutomaticBookClaim(player,latest,2,1));
        }
        try(var store=new SqliteRoundStore(db)) {
            var claim=store.latestAutomaticBookClaim(player).orElseThrow();assertEquals(latest,claim.roundId());assertTrue(claim.delivered());
        }
    }

    @Test void historyParticipantProgressAndClaimIntegrityCorruptionFailsReadOrReplay() throws Exception {
        Path db=database();UUID round=UUID.randomUUID();var write=progress(round,UUID.randomUUID(),Set.of(),setA,false,100);
        try(var store=new SqliteRoundStore(db);var raw=DriverManager.getConnection("jdbc:sqlite:"+db);var sql=raw.createStatement()) {
            create(store,round,1,RulesConfig.defaults(),false);store.recordProgress(write);finish(store,round,1_000_000,true);
            store.ensureAutomaticBookClaim(player,round,1,1);
            sql.execute("UPDATE history_rounds SET challenge_millis=999");assertThrows(SQLException.class,()->store.history(10,0));
            sql.execute("UPDATE progress_events SET payload=x'00'");assertThrows(SQLException.class,()->store.recordProgress(write));
            sql.execute("UPDATE participant_records SET life_sequence=999");assertThrows(SQLException.class,()->store.participantRecord(round,player));
            sql.execute("UPDATE book_deliveries SET delivered=1");assertThrows(SQLException.class,()->store.latestAutomaticBookClaim(player));
        }
    }

    @Test void activeRoundsNeverExposeSeedAndAllListQueriesEnforceBounds() throws Exception {
        try(var store=new SqliteRoundStore(database())) {
            UUID round=UUID.randomUUID();create(store,round,1,RulesConfig.defaults(),false);
            assertTrue(store.roundHistory(round).isEmpty());assertTrue(store.history(10,0).isEmpty());
            assertThrows(IllegalArgumentException.class,()->store.history(1001,0));
            assertThrows(IllegalArgumentException.class,()->store.participantRecords(round,1,-1));
            assertThrows(IllegalArgumentException.class,()->store.advancementIds(round,player,0,0));
            assertThrows(IllegalArgumentException.class,()->store.leaderboard("bad",10));
        }
    }

    @Test void schemaThreeBackfillsExistingSnapshotsAndFailureRollsBackSchemaFourDdl() throws Exception {
        Path db=database();UUID round=UUID.randomUUID();
        try(var store=new SqliteRoundStore(db)) {create(store,round,1,RulesConfig.defaults(),false);finish(store,round,1_000_000,true);}
        try(var raw=DriverManager.getConnection("jdbc:sqlite:"+db);var sql=raw.createStatement()) {
            for(String table:List.of("book_deliveries","round_advancements","advancement_sets","progress_events","participant_records","history_rounds"))sql.execute("DROP TABLE "+table);
            sql.execute("PRAGMA user_version=3");
        }
        try(var store=new SqliteRoundStore(db)) {assertEquals(1,store.history(10,0).size());assertEquals(1,store.leaderboard(RulesConfig.defaults().fingerprint(),10).size());}
        try(var raw=DriverManager.getConnection("jdbc:sqlite:"+db);var sql=raw.createStatement()) {
            for(String table:List.of("book_deliveries","round_advancements","advancement_sets","progress_events","participant_records","history_rounds"))sql.execute("DROP TABLE "+table);
            sql.execute("CREATE TABLE history_rounds(bad TEXT)");sql.execute("PRAGMA user_version=3");
        }
        assertThrows(SQLException.class,()->new SqliteRoundStore(db));
        try(var raw=DriverManager.getConnection("jdbc:sqlite:"+db);var sql=raw.createStatement();var rows=sql.executeQuery("PRAGMA user_version")) {rows.next();assertEquals(3,rows.getInt(1));}
        try(var raw=DriverManager.getConnection("jdbc:sqlite:"+db);var sql=raw.createStatement();var rows=sql.executeQuery("SELECT COUNT(*) FROM sqlite_master WHERE name='participant_records'")) {rows.next();assertEquals(0,rows.getInt(1));}
    }

    @Test void explicitDriverConnectWorksWhenNoDriverIsRegisteredWithDriverManager() throws Exception {
        // This mirrors a Knot bootstrap with JDBC initialized before its service loader sees mod classes.
        new org.sqlite.JDBC();
        var removed = Collections.list(DriverManager.getDrivers()).stream().filter(driver -> driver instanceof org.sqlite.JDBC).toList();
        try {
            for (var driver : removed) DriverManager.deregisterDriver(driver);
            assertThrows(SQLException.class, () -> DriverManager.getConnection("jdbc:sqlite:" + database()));
            try (var store = new SqliteRoundStore(database())) {
                assertTrue(store.latest().isEmpty());
                create(store, UUID.randomUUID(), 1, RulesConfig.defaults(), false);
            }
        } finally { for (var driver : removed) DriverManager.registerDriver(driver); }
    }

    @Test void unknownWinnerHistoryStillValidatesItsOriginalTerminalCausalEvidence() throws Exception {
        Path db=database(); UUID round=UUID.randomUUID();
        try (var store=new SqliteRoundStore(db);var raw=DriverManager.getConnection("jdbc:sqlite:"+db);var sql=raw.createStatement()) {
            create(store,round,1,RulesConfig.defaults(),false);finish(store,round,1_000_000,false);
            assertFalse(store.roundHistory(round).orElseThrow().trustedWinner());
            sql.execute("UPDATE round_terminals SET first_dragon_id='00000000-0000-0000-0000-000000000000'");
            assertThrows(SQLException.class,()->store.roundHistory(round));
        }
    }

    @Test void everyListQueryKeepsAdvancementFingerprintChangesBoundedWithoutDroppingEvidence() throws Exception {
        try(var store=new SqliteRoundStore(database())) {
            UUID round=UUID.randomUUID(); create(store,round,1,RulesConfig.defaults(),false);
            for(int index=0;index<64;index++) assertEquals(CommitResult.APPLIED,store.recordProgress(
                    progress(round,UUID.randomUUID(),Set.of(),String.format("%064x",index),false,100)));
            assertThrows(SQLException.class,()->store.recordProgress(progress(round,UUID.randomUUID(),Set.of(),setA,false,100)));
            assertEquals(64,store.participantRecord(round,player).orElseThrow().advancementSetFingerprints().size());
            finish(store,round,1_000_000,true);
            assertEquals(64,store.history(10,0).getFirst().advancementSetFingerprints().size());
        }
    }

    @Test void currentRoundRulesSeedAndSequenceCannotChangeInLiveOrLegacyFailedAbortedResults() throws Exception {
        try (var store=new SqliteRoundStore(database())) {
            long sequence=1;
            for (RoundResult result : Arrays.asList(null,new RoundResult(RoundResult.Kind.FAILED,null,"failed"),
                    new RoundResult(RoundResult.Kind.ABORTED,null,"admin stop"))) {
                UUID round=UUID.randomUUID();create(store,round,sequence++,RulesConfig.defaults(),false);
                var before=RoundSnapshotCodec.decode(store.load(round).orElseThrow().payload());
                if (result!=null) {
                    var after=snapshot(before,1,RoundPhase.SETTLING,before.participants(),result,false,100);
                    store.commit(new SnapshotWrite(round,0,1,RoundSnapshotCodec.encode(after),UUID.randomUUID(),"FINISH"));
                    before=after;
                }
                var base=before;
                for (int changedField=0;changedField<3;changedField++) {
                    var changed=new RoundSnapshot(round,changedField==0?base.sequence()+100:base.sequence(),
                            changedField==1?base.seed()+1:base.seed(),base.version()+1,
                            changedField==2?base.rules().withMode(GameMode.NORMAL):base.rules(),base.phase(),base.pauseReason(),
                            base.challengeNanos(),base.remainingPhaseNanos(),base.participants(),false,false,base.result());
                    assertThrows(IllegalArgumentException.class,()->store.commit(new SnapshotWrite(round,base.version(),base.version()+1,
                            RoundSnapshotCodec.encode(changed),UUID.randomUUID(),"CHECKPOINT")));
                }
                assertEquals(base.version(),store.load(round).orElseThrow().version());
            }
        }
    }

    private ProgressWrite progress(UUID round,UUID event,Set<String> ids,String fingerprint,boolean end,long nanos) {
        return new ProgressWrite(round,event,player,"Alice",ids,fingerprint,end,nanos);
    }
    private void create(SqliteRoundStore store,UUID round,long sequence,RulesConfig rules,boolean uncertain) throws Exception {
        var participant=new Participant(player,"Alice",ParticipantRole.HUMAN_ACTIVE,true,false,100,0,1,false);
        var lobby=new Participant(UUID.randomUUID(),"LobbyOnly",ParticipantRole.LOBBY,true,false,0,-1,0,false);
        var snapshot=new RoundSnapshot(round,sequence,123,0,rules,RoundPhase.RUNNING,PauseReason.NONE,100,0,Map.of(player,participant,lobby.id(),lobby),false,uncertain,null);
        store.commit(new SnapshotWrite(round,-1,0,RoundSnapshotCodec.encode(snapshot),UUID.randomUUID(),"ROUND_CREATED"));
        assertEquals(1,store.participantCount(round));
    }
    private RoundSnapshot snapshot(RoundSnapshot before,long version,RoundPhase phase,Map<UUID,Participant> participants,RoundResult result,boolean uncertain,long challenge) {
        return new RoundSnapshot(before.roundId(),before.sequence(),before.seed(),version,before.rules(),phase,result==null?PauseReason.NONE:PauseReason.SETTLED,challenge,0,participants,false,uncertain,result);
    }
    private void finish(SqliteRoundStore store,UUID round,long nanos,boolean known) throws Exception {
        var before=RoundSnapshotCodec.decode(store.load(round).orElseThrow().payload()); UUID dragon=UUID.randomUUID();
        var origin=known?Optional.of(new CombatOrigin(round,UUID.randomUUID(),new CombatActor(player,ParticipantRole.HUMAN_ACTIVE,1,0),AttackKind.MELEE,Optional.of(player),List.of())):Optional.<CombatOrigin>empty();
        var death=new CombatEvent(round,UUID.randomUUID(),UUID.randomUUID(),1,dragon,origin,CombatEvent.classify(round,origin),"minecraft:player",Optional.of(player),Optional.of(player),1,true,100);
        var settled=snapshot(before,1,RoundPhase.SETTLING,before.participants(),DragonDeathArbiter.adjudicate(before,death,dragon),before.timingUncertain(),nanos);
        assertEquals(CommitResult.APPLIED,store.commitTerminal(new SnapshotWrite(round,0,1,RoundSnapshotCodec.encode(settled),UUID.randomUUID(),"FINISH"),new EvidenceWrite(round,death.eventId(),"COMBAT",CombatEventCodec.encode(death)),dragon));
    }
}
