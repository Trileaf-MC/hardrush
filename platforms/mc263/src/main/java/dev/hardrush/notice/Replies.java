package dev.hardrush.notice;

import dev.hardrush.round.RoundSession;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Words a rejected {@link RoundSession.Reply} for players; its diagnostic message only reaches the log. */
public final class Replies {
    private static final Logger LOGGER = LoggerFactory.getLogger(Replies.class);
    public static final String REVIVAL_FIRST = "你有复生图腾，现在只能去祭坛复生";
    public static final String CREEPER_NO_QUARRY = "场上还没有三星以上的人类，苦力怕暂时无人可追";
    public static final String CREEPER_LOW_STARS = "苦力怕只追三星以上的人类";
    private Replies() {}

    /** @param rejected wording for a plain rule rejection in the caller's context */
    public static String text(RoundSession.Reply reply, String rejected) {
        return text(reply, rejected, "怪物币不够");
    }

    private static String text(RoundSession.Reply reply, String rejected, String notEnoughCoins) {
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
            case NOT_ENOUGH_COINS -> notEnoughCoins;
            case SKILL_COOLDOWN -> "技能冷却中";
            case HUNT_LOCKED -> "你刚在追猎这名人类时倒下，暂时不能再追他";
            case HUNT_FULL -> "追猎这名人类的怪物已满";
            case PAUSED -> "比赛已暂停，暂时不能出击";
            case CREEPER_LOCKED -> "苦力怕的出击资格已用掉 · 回展位付费解锁";
            case CREEPER_NO_QUARRY -> CREEPER_NO_QUARRY;
            case CREEPER_LOW_STARS -> CREEPER_LOW_STARS;
            case REJECTED -> rejected;
        };
    }

    public static void refuse(ServerPlayer player, RoundSession.Reply reply, String rejected) {
        Notices.refuse(player, text(reply, rejected));
    }

    public static void refuse(ServerPlayer player, RoundSession.Reply reply, String rejected, String notEnoughCoins) {
        Notices.refuse(player, text(reply, rejected, notEnoughCoins));
    }
}
