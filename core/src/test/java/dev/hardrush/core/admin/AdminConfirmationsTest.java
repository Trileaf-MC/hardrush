package dev.hardrush.core.admin;

import static org.junit.jupiter.api.Assertions.*;

import dev.hardrush.core.api.MonotonicClock;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AdminConfirmationsTest {
    private static final AdminActor ALICE = new AdminActor("player:" + UUID.randomUUID(), "Alice");
    private static final AdminActor BOB = new AdminActor("player:" + UUID.randomUUID(), "Bob");
    private static final UUID ROUND = UUID.randomUUID();
    private static final String CONTEXT = "RUNNING:rules123:no-result";
    private static final class Clock implements MonotonicClock {
        long now;
        public long nanoTime() { return now; }
    }
    private final Clock clock = new Clock();
    private final AdminConfirmations confirmations = new AdminConfirmations(clock, 2);
    private static AdminRequest request() {
        return new AdminRequest(ALICE, AdminAction.ABORT, ROUND, CONTEXT,
                "管理员中止：活动提前结束", "立即停止本轮并回大厅，保存记录，不进入通关榜");
    }

    @Test void approvedRequestIsImmutableAndCanOnlyBeConsumedOnce() {
        AdminRequest request = request();
        AdminTicket ticket = confirmations.issue(request);
        assertSame(request, ticket.request());
        assertEquals(1, confirmations.size());
        assertSame(request, confirmations.consume(ticket.id(), ALICE, AdminAction.ABORT, ROUND, CONTEXT));
        assertEquals(0, confirmations.size());
        assertThrows(IllegalStateException.class,
                () -> confirmations.consume(ticket.id(), ALICE, ROUND, CONTEXT));
    }

    @Test void actorActionRoundAndHistoricalEditionCannotBeChangedAtConfirmation() {
        AdminTicket ticket = confirmations.issue(request());
        assertThrows(IllegalStateException.class,
                () -> confirmations.consume(ticket.id(), BOB, ROUND, CONTEXT));
        assertThrows(IllegalStateException.class,
                () -> confirmations.consume(ticket.id(), ALICE, AdminAction.START, ROUND, CONTEXT));
        assertThrows(IllegalStateException.class,
                () -> confirmations.consume(ticket.id(), ALICE, UUID.randomUUID(), CONTEXT));
        assertThrows(IllegalStateException.class,
                () -> confirmations.consume(ticket.id(), ALICE, ROUND, "history-edition:2"));
        assertEquals(1, confirmations.size());
        assertSame(ticket.request(), confirmations.consume(ticket.id(), ALICE, ROUND, CONTEXT));
    }

    @Test void actorUsesStableIdentityRatherThanMutableDisplayName() {
        AdminTicket ticket = confirmations.issue(request());
        AdminActor renamed = new AdminActor(ALICE.identity(), "New Name");
        assertSame(ticket.request(), confirmations.consume(ticket.id(), renamed, ROUND, CONTEXT));
    }

    @Test void expiryUsesMonotonicTimeAndExactOneHundredTwentySecondBoundary() {
        AdminTicket justValid = confirmations.issue(request());
        clock.now = AdminConfirmations.VALID_FOR_NANOS - 1;
        assertSame(justValid.request(), confirmations.consume(justValid.id(), ALICE, ROUND, CONTEXT));
        AdminTicket expired = confirmations.issue(request());
        clock.now += AdminConfirmations.VALID_FOR_NANOS;
        assertThrows(IllegalStateException.class,
                () -> confirmations.consume(expired.id(), ALICE, ROUND, CONTEXT));
        assertEquals(0, confirmations.size());
    }

    @Test void capacityIsBoundedAndExpiredTicketsFreeCapacityWithoutEvictingLiveRequests() {
        AdminTicket first = confirmations.issue(request());
        confirmations.issue(request());
        assertThrows(IllegalStateException.class, () -> confirmations.issue(request()));
        assertEquals(2, confirmations.size());
        assertSame(first.request(), confirmations.consume(first.id(), ALICE, ROUND, CONTEXT));
        confirmations.issue(request());
        clock.now += AdminConfirmations.VALID_FOR_NANOS;
        confirmations.issue(request());
        assertEquals(1, confirmations.size());
    }

    @Test void clockOriginMayBeNegativeAndWrappingNanoTimeStillExpiresCorrectly() {
        clock.now = Long.MAX_VALUE - 1_000_000;
        AdminConfirmations atWrap = new AdminConfirmations(clock);
        AdminTicket ticket = atWrap.issue(request());
        clock.now += 2_000_000;
        assertSame(ticket.request(), atWrap.consume(ticket.id(), ALICE, ROUND, CONTEXT));
        clock.now = -99_000_000;
        AdminConfirmations negativeOrigin = new AdminConfirmations(clock);
        AdminTicket negative = negativeOrigin.issue(request());
        clock.now += 1_000_000;
        assertSame(negative.request(), negativeOrigin.consume(negative.id(), ALICE, ROUND, CONTEXT));
    }

    @Test void backwardsClockInvalidatesExistingAuthorityAndRestartNeverRevivesIt() {
        AdminTicket ticket = confirmations.issue(request());
        clock.now = -1;
        assertThrows(IllegalStateException.class,
                () -> confirmations.consume(ticket.id(), ALICE, ROUND, CONTEXT));
        clock.now = 0;
        assertThrows(IllegalStateException.class,
                () -> confirmations.consume(ticket.id(), ALICE, ROUND, CONTEXT));
        AdminConfirmations restarted = new AdminConfirmations(clock);
        assertThrows(IllegalStateException.class,
                () -> restarted.consume(ticket.id(), ALICE, ROUND, CONTEXT));
    }

    @Test void changingChallengeTimeDoesNotInvalidateAnUnchangedSemanticContext() {
        AdminTicket ticket = confirmations.issue(request());
        clock.now += 60_000_000_000L;
        assertSame(ticket.request(), confirmations.consume(ticket.id(), ALICE, ROUND, CONTEXT));
    }

    @Test void reasonAndPreviewRejectUnboundedOrUnsafeTextWithoutSilentlyChangingIt() {
        assertEquals("原样：管理员中止", AdminText.reason("原样：管理员中止"));
        assertEquals("😀".repeat(512), AdminText.reason("😀".repeat(512)));
        for (String unsafe : new String[] {"", " ", "line\nbreak", "§c改变样式", "\u202E隐藏方向", "\uD800", "a".repeat(513)}) {
            assertThrows(IllegalArgumentException.class, () -> AdminText.reason(unsafe));
        }
        assertThrows(IllegalArgumentException.class,
                () -> new AdminRequest(ALICE, AdminAction.ABORT, ROUND, CONTEXT, "原因", "a".repeat(1025)));
        assertThrows(IllegalArgumentException.class,
                () -> new AdminActor("操作者\t", "Name"));
        assertThrows(IllegalArgumentException.class,
                () -> new AdminConfirmations(clock, 4097));
    }
}
