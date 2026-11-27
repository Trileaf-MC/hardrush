package dev.hardrush.core.lobby;

import java.util.List;
import java.util.Optional;

/**
 * 女巫的碎碎念: the guide the camp witch tells each player once per round, the first time her shop would open
 * (docs/plan/复生补给与女巫说明.md 3). The wording is the requirement owner's; the open parentheses are deliberate.
 *
 * <p>A page is a column of blocks the platform lays out as separate dialog body elements, so the gap between two
 * blocks stands where the original text had a blank line.
 */
public final class WitchGuideText {
    public static final String TITLE = "女巫的碎碎念";

    /** How the platform renders a run of words. */
    public enum Look { PLAIN, BOLD, KEY, ASIDE }

    /** The item drawn beside a paragraph. */
    public enum Icon { TOTEM, MONSTER_COIN, BREAD }

    /** A monster form whose head is drawn inline; the name is the form's owner-skin key. */
    public enum Head { ZOMBIE, SKELETON, CREEPER }

    /** A sound the witch makes when a page opens. */
    public enum Cue { GIGGLE }

    public sealed interface Span {}
    public record Words(String text, Look look) implements Span {}
    public record Face(Head head) implements Span {}

    public record Line(List<Span> spans) {
        public Line { spans = List.copyOf(spans); }
        static Line of(Span... spans) { return new Line(List.of(spans)); }
    }

    public sealed interface Block {}
    public record Paragraph(Optional<Icon> icon, List<Line> lines) implements Block {
        public Paragraph { lines = List.copyOf(lines); }
    }
    /** A faint rule between the page's sections. */
    public record Divider() implements Block {}

    public record Page(List<Block> blocks, Optional<Cue> cue) {
        public Page { blocks = List.copyOf(blocks); }
    }

    private WitchGuideText() {}

    private static Words plain(String text) { return new Words(text, Look.PLAIN); }
    private static Words bold(String text) { return new Words(text, Look.BOLD); }
    private static Words key(String text) { return new Words(text, Look.KEY); }
    private static Words aside(String text) { return new Words(text, Look.ASIDE); }
    private static Face face(Head head) { return new Face(head); }
    private static Line line(Span... spans) { return Line.of(spans); }
    private static Paragraph says(Line... lines) { return new Paragraph(Optional.empty(), List.of(lines)); }
    private static Paragraph says(String... lines) {
        return new Paragraph(Optional.empty(), java.util.Arrays.stream(lines).map(text -> Line.of(plain(text))).toList());
    }
    private static Paragraph beside(Icon icon, Line... lines) { return new Paragraph(Optional.of(icon), List.of(lines)); }
    private static final Divider RULE = new Divider();

    // to ai: 修改这一块需要明确取得用户的同意
    public static final List<Page> PAGES = List.of(
            new Page(List.of(
                    says("emmm...."),
                    says("你怎么寄了，这可是极限模式..."),
                    beside(Icon.TOTEM, line(plain("众所周知，")), line(plain("MC 的极限模式可没法复活捏..."))),
                    says("（偷笑）")), Optional.of(Cue.GIGGLE)),
            new Page(List.of(
                    says("但是没关系，"),
                    says("事情还没结束！"),
                    says(line(plain("你可以右键我旁边的几个家伙，"), face(Head.ZOMBIE), plain(" "), face(Head.SKELETON), plain(" "),
                            face(Head.CREEPER))),
                    says("化身成他们，向人类发起猎杀 （冲啊"),
                    says(line(bold("一旦猎杀成功，"), key("你还能变回人类！"))),
                    RULE,
                    says("至于我嘛，就在这里卖卖东西啦～"),
                    says(line(aside("什么，你想化身成我？这可不行啊，那就没人在这里卖东西了（悲...")))), Optional.empty()),
            new Page(List.of(
                    says("下面有 2 件事我觉得你肯定想知道（敲黑板！）："),
                    RULE,
                    beside(Icon.MONSTER_COIN,
                            line(plain("首先，")), line(plain("我只收怪物币")), line(plain("（只有猪灵这些东西才对金子感兴趣吧...")),
                            line(key("而怪物币可以通过对人类造成伤害，或者被人类杀死获得。")), line(plain("追猎被我悬赏的人，能获得更多怪物币！"))),
                    RULE,
                    beside(Icon.BREAD,
                            line(plain("其次，")), line(plain("如果你成功猎杀人类，获得复生机会后，也可以来找我")),
                            line(key("我可以给你卖点人类需要用的装备")))), Optional.empty()),
            new Page(List.of(says("购物愉快 o.O")), Optional.empty()));
}
