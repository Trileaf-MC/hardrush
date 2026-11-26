package dev.hardrush.core.lobby;

import dev.hardrush.core.config.GameMode;
import dev.hardrush.core.config.RulesConfig;
import dev.hardrush.core.hunt.BellAlarm;
import dev.hardrush.core.hunt.LateShelter;
import dev.hardrush.core.round.HuntPressure;
import dev.hardrush.core.round.MonsterCoins;
import dev.hardrush.core.round.MonsterWorth;
import dev.hardrush.core.round.RoundEngine;
import java.util.ArrayList;
import java.util.List;

/**
 * The rule lectern's book (docs/plan/玩家消息文案.md, N1). Every number comes from the round's rules. A page is kept
 * well under what one vanilla book page shows, since a written book never scrolls. {@code {key:<mapping>}} marks a
 * control the platform renders as the reader's own key binding.
 */
public final class RuleBookText {
    public static final String TITLE = "HardRush 规则";
    private RuleBookText() {}

    public static List<String> pages(RulesConfig rules) {
        boolean hardcore = rules.mode() == GameMode.HARDCORE;
        var pages = new ArrayList<String>();
        pages.add("HardRush\n\n人类击败末影龙即通关，对末影龙打出最后一击的人类是胜者。\n\n本轮模式：" + (hardcore ? "极限挑战" : "普通模式"));
        pages.add("开局与加入\n\n开局前走进集结区即准备，走出即取消。\n\n挑战开始后，在集结区站 4 秒可以空手中途加入，落在远离出生点的荒野。"
                + (LateShelter.enabled(rules) ? "落地后 " + LateShelter.duration(rules) + "内怪物无法追猎你，离线不计时。" : ""));
        pages.add("离线与救场\n\n人类离线时保留资格"
                + (hardcore ? "，但被玩家打中后 " + dev.hardrush.core.round.CombatDeparture.windowSeconds() + " 秒内断线算作阵亡" : "")
                + "。所有人类都离线时等待 " + seconds(RoundEngine.reconnectMillis(rules))
                + " 秒，期间有人重连、复生或中途加入就继续，否则本轮结束。");
        pages.add("黄昏契约\n\n开局后到第一次日落前，玩家之间不能互相伤害。"
                + (hardcore ? "\n\n日落后，人类被人类击杀时会掉落 1 个复生图腾。" : ""));
        if (!hardcore) {
            pages.add("阵亡\n\n普通模式下阵亡后按原版重生。");
        } else {
            pages.add("阵亡\n\n极限挑战中阵亡后回到营地，可以到庭院的角色展位选择僵尸、骷髅或苦力怕，化身怪物追猎人类。\n\n"
                    + "被怪物玩家杀死时，身上的东西和经验都不掉落，从祭坛复生时原样带回。");
            pages.add("追猎\n\n右键庭院里的目标模型，开始寻找目标附近的出击位置，再次右键取消。僵尸和骷髅落地时，目标会听到一声洞穴音；苦力怕悄无声息。\n\n"
                    + "出击后右键回响碎片，边走边引导 " + seconds(RoundEngine.returnChannelMillis(rules))
                    + " 秒返回营地，本次物资暂存到下次。");
            pages.add("追迹罗盘\n\n追迹罗盘的指针指向你的目标，发光时右键，边走边引导 " + seconds(RoundEngine.retrackChannelMillis(rules))
                    + " 秒就能循迹到目标附近。\n\n受伤或动手会打断引导；落地后罗盘冷却 " + seconds(RoundEngine.retrackCooldownMillis(rules)) + " 秒。");
            pages.add("提防与满员\n\n怪物出击中倒下后，这次追的人类和击杀它的人类会提防它一阵：一星 " + seconds(HuntPressure.lockMillis(1))
                    + " 秒，五星 " + seconds(HuntPressure.lockMillis(5)) + " 秒。返回营地不算。\n\n"
                    + "人类有几颗星，最多就有几只怪物同时追他，满员时排队。\n\n人类用打火石或火焰弹右键苦力怕，可以强行点燃它。");
            pages.add("钟声\n\n敲钟时，若 " + BellAlarm.RESONATE_RADIUS + " 格内有怪物玩家，钟会嗡嗡共鸣，随后 "
                    + BellAlarm.REVEAL_RADIUS + " 格内的怪物玩家发光 " + BellAlarm.GLOW_SECONDS + " 秒，随从不发光。女巫、掠夺者等原版袭击者也会引起共鸣。\n\n"
                    + "让怪物显形后，这口钟 " + BellAlarm.restSeconds() + " 秒内只响不共鸣。");
            pages.add("角色\n\n僵尸追二星以上的人类时自带刷怪蛋，困难难度下能空手敲开木门（约 12 秒），泡在水里会化身溺尸。\n\n"
                    + "骷髅潜行拉满弓触发三连射，陷进细雪会化身流浪者。\n\n苦力怕按住引爆器（打火石）引爆，也能用它点燃原版苦力怕。");
            if (MonsterCoins.creeperPaid(rules)) pages.add("苦力怕解锁\n\n苦力怕每次出击要先右键展位的苦力怕石像，付 "
                    + MonsterCoins.creeperUnlockPrice(rules) + " 怪物币解锁。它只追三星以上的人类。\n\n"
                    + "返回营地不用重新付；自爆或倒下后要重新解锁。");
            pages.add("挖掘与虫穴\n\n僵尸和骷髅空手按住左键，一次挖开 1×2 的石头、泥土、下界岩、末地石等天然方块，最长 "
                    + decimalSeconds(RoundEngine.digMillis(rules)) + " 秒，不掉落。苦力怕挖不动。\n\n"
                    + "出击找不到能走到人类身边的落点时，怪物会藏进人类旁边的石头里，挖开墙破石而出。");
            if (MonsterCoins.enabled(rules)) pages.add("怪物币\n\n伤害人类、被人类击杀都能获得怪物币；击杀二星以上的人类，或逼他用掉图腾，还有一笔赏金。\n\n"
                    + "怪物币可以在女巫处购买出击增强。药水出击时自己喝，药水盲盒喝下才揭晓；亡灵喝伤害药水会回血。"
                    + "试炼钥匙可以在宝库开出一次性装备。拿到复生图腾后，女巫改卖复生补给。");
            if (dev.hardrush.core.round.Bounty.enabled(rules)) pages.add("悬赏\n\n速通推进得越远，悬赏星级越高，击杀怪物玩家也会加一点；每条命重新计算。\n\n"
                    + "星级越高，能同时追他的怪物越多，怪物出击带得越好、三连射冷却越短，打他也越赚：★★★★★ 是 ★☆☆☆☆ 的 " + multiplier(rules)
                    + " 倍。头戴不祥旗帜的是头号悬赏。");
            if (MonsterWorth.enabled(rules)) pages.add("身价\n\n出击时的经验等级就是身价：从 " + (long) value(rules, MonsterWorth.BASE_LEVEL, 10)
                    + " 级起，伤害人类会上涨，最高 " + (long) value(rules, MonsterWorth.MAX_LEVEL, 30) + " 级。\n\n被玩家击杀时掉出经验，满 "
                    + (long) value(rules, MonsterWorth.BOOK_MIN_LEVEL, 15) + " 级再掉一本附魔书。");
            pages.add("复生图腾\n\n人类每阵亡一次、每用掉一个图腾，都会放出 1 个：怪物击杀或逼出的直接归怪物，其余掉在原地，"
                    + "人类和出击中的怪物都能捡，但放出它的那条命捡不回来。\n\n"
                    + "没有被人击杀或逼出时，掉落会公布坐标。图腾不能丢弃，阵亡时自己的图腾不会掉落。");
            pages.add("复生\n\n把复生图腾拿在手上阵亡时原地复生，消耗 1 个。\n\n"
                    + "没拿在手上就阵亡，可到营地北侧的祭坛站上金块 4 秒复生，"
                    + (MonsterCoins.enabled(rules) ? "可先用怪物币在女巫处买复生补给。" : "空手出发。")
                    + "落地获得图腾之力庇护：生命恢复 II、伤害吸收 IV、抗火，持续 "
                    + seconds(dev.hardrush.core.round.WitchRecord.BLESSING_MILLIS) + " 秒。");
            pages.add("救场\n\n所有人类阵亡后进入 " + seconds(RoundEngine.rescueMillis(rules))
                    + " 秒救场：有图腾的怪物可以复生，也可以从集结区加入。");
        }
        pages.add("结局\n\n通关后所有人到末地为胜者庆祝，最长 " + seconds(RoundEngine.successCelebrationMillis(rules))
                + " 秒，胜者跳进末地传送门即结束。\n\n历届战绩在书屋查看。");
        pages.add("退出本轮\n\n按住 {key:key.sneak} 再按 {key:key.swapOffhand}，打开确认菜单。\n\n退出后本轮不能再加入，可以用望远镜观战，下一轮恢复。");
        return List.copyOf(pages);
    }

    private static double value(RulesConfig rules, String key, double fallback) {
        return rules.combatValues().getOrDefault(key, fallback);
    }

    private static long seconds(double millis) { return Math.max(1, (long) Math.ceil(millis / 1000)); }

    /** "7.5", "3": tenths only when the setting has them. */
    private static String decimalSeconds(long millis) {
        long tenths = Math.max(1, (millis + 99) / 100);
        return tenths % 10 == 0 ? Long.toString(tenths / 10) : tenths / 10 + "." + tenths % 10;
    }

    private static String multiplier(RulesConfig rules) {
        return java.math.BigDecimal.valueOf(dev.hardrush.core.round.Bounty.maxPermille(rules), 3).stripTrailingZeros().toPlainString();
    }
}
