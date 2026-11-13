package dev.hardrush.core.lobby;

import dev.hardrush.core.config.GameMode;
import dev.hardrush.core.config.RulesConfig;
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
        pages.add("开局与加入\n\n开局前走进集结区即准备，走出即取消。\n\n挑战开始后，在集结区站 4 秒可以空手中途加入，落在远离出生点的荒野，没有出生保护。");
        pages.add("离线与救场\n\n人类离线时保留资格。所有人类都离线时进入 " + seconds(value(rules, "round.rescueMillis", 60_000))
                + " 秒救场，期间有人重连就继续，否则本轮结束。");
        pages.add("黄昏契约\n\n开局后到第一次日落前，玩家之间不能互相伤害。");
        if (!hardcore) {
            pages.add("阵亡\n\n普通模式下阵亡后按原版重生。");
        } else {
            pages.add("阵亡\n\n极限挑战中阵亡后回到营地，可以到庭院的角色展位选择僵尸、骷髅或苦力怕，化身怪物追猎人类。");
            pages.add("追猎\n\n右键庭院里的目标模型，开始寻找目标附近的出击位置，再次右键取消。\n\n出击后右键回响碎片，原地不动 "
                    + seconds(value(rules, "monster.returnChannelMillis", 5_000))
                    + " 秒返回营地，本次物资暂存到下次。追迹罗盘发光时右键，可以循迹到目标附近。");
            pages.add("角色\n\n僵尸自带僵尸刷怪蛋，困难难度下能空手敲开木门（约 12 秒），泡在水里会化身溺尸。\n\n"
                    + "骷髅潜行拉满弓触发三连射，陷进细雪会化身流浪者。\n\n苦力怕按住引爆器引爆。");
            if (MonsterCoins.enabled(rules)) pages.add("怪物币\n\n伤害人类、近期受玩家伤害后阵亡都能获得怪物币，领先的人类身上有悬赏。\n\n"
                    + "怪物币可以在女巫处购买出击增强，试炼钥匙可以在宝库开出一次性装备。");
            if (MonsterWorth.enabled(rules)) pages.add("身价\n\n出击时的经验等级就是身价：从 " + (long) value(rules, MonsterWorth.BASE_LEVEL, 10)
                    + " 级起，伤害人类会上涨，最高 " + (long) value(rules, MonsterWorth.MAX_LEVEL, 30) + " 级。\n\n被玩家击杀时掉出经验，满 "
                    + (long) value(rules, MonsterWorth.BOOK_MIN_LEVEL, 15) + " 级再掉一本附魔书。");
            pages.add("复生\n\n击杀一名人类获得 1 个复生图腾。有图腾时只能去营地北侧的祭坛复生：站上金块 4 秒，空手回到挑战世界，怪物币清空。\n\n"
                    + "图腾可以丢给同伴，落地 5 分钟作废。");
            pages.add("救场\n\n所有人类阵亡后进入 " + seconds(value(rules, "round.rescueMillis", 60_000))
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
}
