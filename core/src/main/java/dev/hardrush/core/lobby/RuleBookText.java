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
        pages.add("离线与救场\n\n人类离线时保留资格。所有人类都离线时等待 " + seconds(RoundEngine.reconnectMillis(rules))
                + " 秒，期间有人重连、复生或中途加入就继续，否则本轮结束。");
        pages.add("黄昏契约\n\n开局后到第一次日落前，玩家之间不能互相伤害。"
                + (hardcore ? "\n\n日落后，人类被人类击杀时会掉落 1 个复生图腾。" : ""));
        if (!hardcore) {
            pages.add("阵亡\n\n普通模式下阵亡后按原版重生。");
        } else {
            pages.add("阵亡\n\n极限挑战中阵亡后回到营地，可以到庭院的角色展位选择僵尸、骷髅或苦力怕，化身怪物追猎人类。");
            pages.add("追猎\n\n右键庭院里的目标模型，开始寻找目标附近的出击位置，再次右键取消。\n\n出击后右键回响碎片，原地不动 "
                    + seconds(value(rules, "monster.returnChannelMillis", 5_000))
                    + " 秒返回营地，本次物资暂存到下次。\n\n追迹罗盘的指针指向你的目标，发光时右键，边走边引导同样的时长就能循迹到目标附近。");
            pages.add("角色\n\n僵尸自带僵尸刷怪蛋，困难难度下能空手敲开木门（约 12 秒），泡在水里会化身溺尸。\n\n"
                    + "骷髅潜行拉满弓触发三连射，陷进细雪会化身流浪者。\n\n苦力怕按住引爆器引爆。");
            pages.add("挖掘与虫穴\n\n僵尸和骷髅空手按住左键，一次挖开 1×2 的石头、泥土、下界岩、末地石等天然方块，最长 "
                    + decimalSeconds(RoundEngine.digMillis(rules)) + " 秒，不掉落。苦力怕挖不动。\n\n"
                    + "出击找不到能走到人类身边的落点时，怪物会藏进人类旁边的石头里，挖开墙破石而出。");
            if (MonsterCoins.enabled(rules)) pages.add("怪物币\n\n伤害人类、近期受玩家伤害后阵亡都能获得怪物币，领先的人类身上有悬赏。\n\n"
                    + "怪物币可以在女巫处购买出击增强，试炼钥匙可以在宝库开出一次性装备。");
            if (MonsterWorth.enabled(rules)) pages.add("身价\n\n出击时的经验等级就是身价：从 " + (long) value(rules, MonsterWorth.BASE_LEVEL, 10)
                    + " 级起，伤害人类会上涨，最高 " + (long) value(rules, MonsterWorth.MAX_LEVEL, 30) + " 级。\n\n被玩家击杀时掉出经验，满 "
                    + (long) value(rules, MonsterWorth.BOOK_MIN_LEVEL, 15) + " 级再掉一本附魔书。");
            pages.add("复生图腾\n\n人类被人类击杀或逼出图腾时会掉落 1 个，人类捡起即得；怪物击杀人类或逼出图腾直接获得 1 个。\n\n"
                    + "图腾不能丢弃或转赠，阵亡时自己的图腾不会掉落。");
            pages.add("复生\n\n把复生图腾拿在手上阵亡时原地复生，消耗 1 个。\n\n"
                    + "没拿在手上就阵亡，可到营地北侧的祭坛站上金块 4 秒复生，空手出发，怪物币清空。");
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
}
