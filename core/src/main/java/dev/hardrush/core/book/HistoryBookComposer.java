package dev.hardrush.core.book;

import dev.hardrush.core.api.BookPort;
import dev.hardrush.core.record.LeaderboardEntry;
import dev.hardrush.core.record.ParticipantRecord;
import dev.hardrush.core.record.RoundHistory;
import dev.hardrush.core.record.RoundResultText;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Each request reads at most one bounded roster slice; the database remains the complete history. */
public final class HistoryBookComposer {
    public static final int PARTICIPANTS_PER_VOLUME = 40;
    public static final int LEADERBOARD_SIZE = 10;
    public static final int MAX_VOLUMES = 10_000;

    private HistoryBookComposer() { }

    public static int volumeCount(long participantCount) {
        if (participantCount < 0 || participantCount > (long) MAX_VOLUMES * PARTICIPANTS_PER_VOLUME) {
            throw new IllegalArgumentException("Participant count exceeds book volume bound");
        }
        return Math.max(1, (int) ((participantCount + PARTICIPANTS_PER_VOLUME - 1) / PARTICIPANTS_PER_VOLUME));
    }

    public static BookPort.Book compose(RoundHistory history, String currentRulesFingerprint,
            int volume, long participantCount, List<ParticipantRecord> participants,
            List<LeaderboardEntry> leaderboard) {
        return compose(history, currentRulesFingerprint, volume, participantCount, participants, leaderboard, Optional.empty());
    }

    public static BookPort.Book compose(RoundHistory history, String currentRulesFingerprint,
            int volume, long participantCount, List<ParticipantRecord> participants,
            List<LeaderboardEntry> leaderboard, Optional<ParticipantRecord> winnerRecord) {
        Objects.requireNonNull(history, "history");
        if (currentRulesFingerprint == null || !currentRulesFingerprint.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("Invalid leaderboard fingerprint");
        }
        int volumes = volumeCount(participantCount);
        if (volume < 1 || volume > volumes || participants.size() > PARTICIPANTS_PER_VOLUME
                || leaderboard.size() > LEADERBOARD_SIZE) throw new IllegalArgumentException("Invalid book volume");
        long offset = (long) (volume - 1) * PARTICIPANTS_PER_VOLUME;
        int expected = (int) Math.min(PARTICIPANTS_PER_VOLUME, participantCount - offset);
        if (participants.size() != expected) throw new IllegalArgumentException("Incomplete book roster slice");
        var paragraphs = new ArrayList<String>();
        // One set of books per round, told apart by volume; a correction reissues them as a revision.
        paragraphs.add("HardRush 战绩\n第 " + history.sequence() + " 轮 · 第 " + volume + "/" + volumes + " 卷"
                + (history.edition() > 1 ? "\n第 " + (history.edition() - 1) + " 次修订" : ""));
        if (volume == 1) {
            boolean completed = history.result().kind() == dev.hardrush.core.round.RoundResult.Kind.COMPLETED;
            String winner = !completed ? "无" : history.result().winner() == null ? "有待管理员核实" : "未知";
            for (ParticipantRecord participant : participants) {
                if (participant.playerId().equals(history.result().winner())) winner = SafeBookText.literal(participant.playerName(), 64);
            }
            if (winnerRecord.isPresent()) {
                ParticipantRecord record = winnerRecord.orElseThrow();
                if (!record.playerId().equals(history.result().winner())) throw new IllegalArgumentException("Winner identity mismatch");
                winner = SafeBookText.literal(record.playerName(), 64);
            }
            paragraphs.add("上一轮结果：" + dev.hardrush.core.record.LibraryText.outcome(history.result())
                    + "\n模式：" + dev.hardrush.core.record.LibraryText.mode(history.rules().mode())
                    + "\n用时：" + duration(history.challengeMillis()) + "\n胜者：" + winner
                    + (completed ? "\n成绩：" + (history.leaderboardEligible() ? "有效" : "有待管理员核实") : "")
                    + "\n" + RoundResultText.reason(history.result()));
            paragraphs.add("当前规则下的前 10 名\n用时相同并列；有待核实的成绩不入榜");
            if (leaderboard.isEmpty()) paragraphs.add("还没有通关成绩");
            for (LeaderboardEntry entry : leaderboard) {
                if (!entry.round().rulesFingerprint().equals(currentRulesFingerprint) || !entry.round().leaderboardEligible()) {
                    throw new IllegalArgumentException("Leaderboard crossed rules or unverified result");
                }
                paragraphs.add("#" + entry.rank() + " " + SafeBookText.literal(entry.winnerName(), 64)
                        + "\n第 " + entry.round().sequence() + " 轮 · " + duration(entry.round().challengeMillis()));
            }
        }
        paragraphs.add("参赛名单 " + (participantCount == 0 ? "0" : (offset + 1) + "～" + (offset + participants.size()))
                + "/" + participantCount);
        for (int index = 0; index < participants.size(); index++) {
            ParticipantRecord player = participants.get(index);
            paragraphs.add((offset + index + 1) + ". " + SafeBookText.literal(player.playerName(), 64)
                    + "\n人类时长 " + duration(player.humanMillis())
                    + "\n进度 " + player.advancementCount() + " · 死亡 " + player.deathCount() + " · 复生 " + player.revivalCount()
                    + "\n末地：" + (player.firstEndNanos() == null ? "未到达" : duration(player.firstEndNanos() / 1_000_000)));
        }
        paragraphs.add("战绩书屋\n回到营地右键书屋讲台，可以查看各轮结果、参赛名单和排行榜，并领取任意一卷。\n成绩修正后，重新领取即可得到最新版");
        String title = SafeBookText.literal("HardRush 第" + history.sequence() + "轮 第" + volume + "卷", 32);
        // The native title limit is UTF-16 length, not code points.
        if (title.length() > 32) title = title.substring(0, 32);
        return new BookPort.Book(history.roundId(), Math.toIntExact(history.edition()), volume, title, "HardRush",
                SafeBookText.paginate(paragraphs));
    }

    public static String duration(long millis) {
        if (millis < 0) throw new IllegalArgumentException("Negative duration");
        long seconds = millis / 1_000;
        return seconds / 3_600 + ":" + two(seconds / 60 % 60) + ":" + two(seconds % 60) + "." + three(millis % 1_000);
    }

    private static String two(long value) { return value < 10 ? "0" + value : Long.toString(value); }
    private static String three(long value) { return value < 10 ? "00" + value : value < 100 ? "0" + value : Long.toString(value); }
}
