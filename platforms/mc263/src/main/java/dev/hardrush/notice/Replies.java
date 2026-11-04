package dev.hardrush.notice;

import dev.hardrush.round.RoundSession;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Words a rejected {@link RoundSession.Reply} for players; its diagnostic message only reaches the log. */
public final class Replies {
    private static final Logger LOGGER = LoggerFactory.getLogger(Replies.class);
    public static final String REVIVAL_FIRST = "你有复生图腾，现在只能去祭坛复生";
    private Replies() {}

    /** @param rejected wording for a plain rule rejection in the caller's context */
    public static String text(RoundSession.Reply reply, String rejected) {
        if (reply.success()) throw new IllegalArgumentException("Only a rejection has player wording");
        LOGGER.debug("Player request rejected ({}): {}", reply.refusal(), reply.message());
        return switch (reply.refusal()) {
            case BUSY -> "服务器正忙，稍后再试";
            case NOT_READY -> "挑战世界还在准备中";
            case RESTART_WAIT -> "正在等待重启前已准备的玩家回来";
            case FROZEN -> "本轮即将结束";
            case SUSPENDED -> "比赛已暂停 · 等待管理员处理";
            case LEFT_ASSEMBLY -> "你离开了集结区，准备已取消";
            case REVIVAL_FIRST -> REVIVAL_FIRST;
            case NOT_ENOUGH_COINS -> "怪物币不够";
            case SKILL_COOLDOWN -> "技能冷却中";
            case REJECTED -> rejected;
        };
    }

    public static void refuse(ServerPlayer player, RoundSession.Reply reply, String rejected) {
        Notices.refuse(player, text(reply, rejected));
    }
}
