package dev.hardrush.core.lobby;

import dev.hardrush.core.config.RulesConfig;
import dev.hardrush.core.round.MonsterCoins;
import java.util.ArrayList;
import java.util.List;

/**
 * 女巫的碎碎念: the guide the camp witch tells each player once per round, the first time her shop would open
 * (docs/plan/复生补给与女巫说明.md 3). The wording is the requirement owner's; the open parentheses are deliberate.
 */
public final class WitchGuideText {
    public static final String TITLE = "女巫的碎碎念";

    /** How the platform renders a line. */
    public enum Look { PLAIN, BOLD, ASIDE }

    public record Line(String text, Look look) {
        static Line plain(String text) { return new Line(text, Look.PLAIN); }
    }

    private WitchGuideText() {}

    /** The pages as written, for rounds whose creepers hunt for free. */
    public static final List<List<Line>> PAGES = List.of(
            List.of(Line.plain("emmm...."), Line.plain(""), Line.plain("你怎么寄了，这可是极限模式..."), Line.plain(""),
                    Line.plain("众所周知，"), Line.plain("MC 的极限模式可没法复活捏..."), Line.plain(""), Line.plain("（偷笑）")),
            List.of(Line.plain("但是没关系，"), Line.plain(""), Line.plain("事情还没结束！"), Line.plain(""),
                    Line.plain("你可以右键我旁边的三个家伙，"), Line.plain(""), Line.plain("化身成他们，向人类发起猎杀 （冲啊"), Line.plain(""),
                    new Line("一旦猎杀成功，你还能变回人类！", Look.BOLD), Line.plain(""),
                    Line.plain("至于我嘛，就在这里卖卖东西啦～"), Line.plain(""),
                    new Line("什么，你想化身成我？这可不行啊，那就没人在这里卖东西了（悲...", Look.ASIDE)),
            List.of(Line.plain("下面有 2 件事我觉得你肯定想知道（敲黑板！）："), Line.plain(""),
                    Line.plain("首先，"), Line.plain("我只收怪物币"), Line.plain("（只有猪灵这些东西才对金子感兴趣吧..."),
                    Line.plain("而怪物币可以通过对人类造成伤害，或者被人类杀死获得。"), Line.plain("追猎被我悬赏的人，能获得更多怪物币！"),
                    Line.plain(""), Line.plain("其次，"), Line.plain("如果你成功猎杀人类，获得复生机会后，也可以来找我"),
                    Line.plain("我可以给你卖点人类需要用的装备")),
            // docs/plan/女巫药水与药水盲盒.md §7.
            List.of(Line.plain("对了，我的药水都要你出击时自己喝下去才有用"), Line.plain(""),
                    Line.plain("亡灵喝伤害药水反而会回血哦（嘿嘿"), Line.plain(""),
                    Line.plain("购物愉快 o.O")));

    /** The round's pages: where creepers are paid for, the second page says what one costs (docs/plan/苦力怕付费解锁.md). */
    public static List<List<Line>> pages(RulesConfig rules) {
        if (!MonsterCoins.creeperPaid(rules)) return PAGES;
        var second = new ArrayList<>(PAGES.get(1));
        int after = second.indexOf(Line.plain("化身成他们，向人类发起猎杀 （冲啊")) + 2;
        second.add(after, Line.plain("不过苦力怕要先付我 " + MonsterCoins.creeperUnlockPrice(rules) + " 怪物币才肯出门（它一炸就没了嘛..."));
        second.add(after + 1, Line.plain(""));
        var pages = new ArrayList<>(PAGES);
        pages.set(1, List.copyOf(second));
        return List.copyOf(pages);
    }
}
