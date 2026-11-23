package dev.hardrush.core.combat;

import static org.junit.jupiter.api.Assertions.*;

import dev.hardrush.core.api.DamagePort.AttackKind;
import dev.hardrush.core.round.ParticipantRole;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RevengeLedgerTest {
    private final UUID round = UUID.randomUUID();
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();
    private final UUID carol = UUID.randomUUID();

    private Optional<CombatOrigin> human(UUID id) { return by(id, ParticipantRole.HUMAN_ACTIVE, round); }
    private Optional<CombatOrigin> monster(UUID id) { return by(id, ParticipantRole.MONSTER_DEPLOYED, round); }
    private static Optional<CombatOrigin> by(UUID id, ParticipantRole role, UUID round) {
        return Optional.of(new CombatOrigin(round, UUID.randomUUID(),
                new CombatActor(id, role, 1, role == ParticipantRole.MONSTER_DEPLOYED ? 1 : 0),
                AttackKind.MELEE, Optional.empty(), List.of()));
    }

    @Test void theVictimTurnedMonsterAvengesItselfOnceOnItsKiller() {
        var ledger = new RevengeLedger();
        assertEquals(Optional.empty(), ledger.humanDied(round, bob, human(alice)), "Alice killed Bob as a human");
        assertEquals(Optional.of(bob), ledger.humanDied(round, alice, monster(bob)), "Monster Bob kills human Alice");
        assertEquals(Optional.empty(), ledger.humanDied(round, alice, monster(bob)), "The debt is settled once");
    }

    @Test void aMonsterKillerIsOwedToo() {
        var ledger = new RevengeLedger();
        ledger.humanDied(round, bob, monster(alice));
        assertEquals(Optional.of(bob), ledger.humanDied(round, alice, monster(bob)),
                "Alice, a monster then and revived since, dies to the monster she made");
    }

    @Test void onlyTheMostRecentHumanDeathCountsAndAnUncreditedDeathClearsIt() {
        var ledger = new RevengeLedger();
        ledger.humanDied(round, bob, human(alice));
        ledger.humanDied(round, bob, human(carol));
        assertEquals(Optional.empty(), ledger.humanDied(round, alice, monster(bob)), "Bob's last death was Carol's");
        ledger.humanDied(round, bob, human(alice));
        ledger.humanDied(round, bob, Optional.empty());
        assertEquals(Optional.empty(), ledger.humanDied(round, alice, monster(bob)), "A fall with nobody credited clears it");
    }

    @Test void onlyADeployedMonsterAvengesAndOnlyOnItsOwnKiller() {
        var ledger = new RevengeLedger();
        ledger.humanDied(round, bob, human(alice));
        assertEquals(Optional.empty(), ledger.humanDied(round, carol, monster(bob)), "Carol owed Bob nothing");
        assertEquals(Optional.empty(), ledger.humanDied(round, alice, human(bob)), "Bob as a human again is no revenge");
    }

    @Test void avengedVictimsOweTheirAvengerInTurn() {
        var ledger = new RevengeLedger();
        ledger.humanDied(round, bob, human(alice));
        ledger.humanDied(round, alice, monster(bob));
        assertEquals(Optional.of(alice), ledger.humanDied(round, bob, monster(alice)));
    }

    @Test void anotherRoundOrAStaleCreditForgetsTheDebt() {
        var ledger = new RevengeLedger();
        ledger.humanDied(round, bob, human(alice));
        var next = UUID.randomUUID();
        assertEquals(Optional.empty(), ledger.humanDied(next, alice, by(bob, ParticipantRole.MONSTER_DEPLOYED, next)));
        ledger.humanDied(next, bob, human(alice));
        assertEquals(Optional.empty(), ledger.humanDied(next, alice, monster(bob)), "A credit from another round is nobody's");
        assertEquals(Optional.empty(), ledger.humanDied(next, carol, human(carol)), "Killing oneself owes nobody");
    }
}
