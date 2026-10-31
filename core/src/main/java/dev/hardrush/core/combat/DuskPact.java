package dev.hardrush.core.combat;

/** First sunrise-to-dusk grace period. Display thresholds are real seconds at the current clock rate. */
public final class DuskPact {
    /** Vanilla's {@code minecraft:night} marker: undead stopped burning at 12542 and beds already work. */
    public static final long DUSK_TICKS = 13_000;
    public static final int WARNING_SECONDS = 180;
    public static final int COMBAT_SECONDS = 60;
    public static final int FINAL_SECONDS = 10;
    public static final String TITLE = "黄昏契约";
    public static final String INTRO = "契约保护你免受其他玩家伤害，持续到日落";
    public static final String RELEASED = "黄昏契约已解除";
    public static final String BLOCKED = "黄昏契约期间，你不可攻击其他玩家";
    public enum Color { GREEN, YELLOW, RED }
    /** {@code tab} is always available; the bossbar only appears from the warning onward. */
    public record Display(Color color, String text, String tab, String blocked, long seconds, float progress, boolean advancing) {
        public boolean bar() { return color != Color.GREEN; }
    }

    private DuskPact() {}

    public static String releasedSubtitle(boolean hardcore) {
        return hardcore ? "夜幕降临 · 极限追猎开始" : "夜幕降临 · 祝你好运";
    }

    public static Display display(long ticks, float partialTick, float clockRate, double ticksPerSecond, boolean paused) {
        double remaining = Math.max(0, DUSK_TICKS - ticks - partialTick);
        boolean advancing = !paused && clockRate > 0 && ticksPerSecond > 0;
        double realRemaining = advancing ? remaining / (clockRate * ticksPerSecond) : Double.MAX_VALUE;
        long seconds = advancing ? (long)Math.ceil(realRemaining) : Long.MAX_VALUE;
        Color color = seconds <= COMBAT_SECONDS ? Color.RED : seconds <= WARNING_SECONDS ? Color.YELLOW : Color.GREEN;
        String time = advancing ? "%02d:%02d".formatted(seconds / 60, seconds % 60) : "昼夜暂停";
        String text = switch (color) {
            case GREEN -> "黄昏契约 · 日落前暂息纷争 · " + time;
            case YELLOW -> "夜幕将至 · 距猎杀开始还有 " + time;
            case RED -> seconds <= FINAL_SECONDS ? "准备作战 · " + seconds : "准备作战";
        };
        String blocked = advancing ? BLOCKED + " · 剩余 " + time : BLOCKED;
        // The bar appears full at the warning and drains over the final real minutes.
        float progress = (float)Math.clamp(advancing ? realRemaining / WARNING_SECONDS : remaining / DUSK_TICKS, 0, 1);
        return new Display(color, text, "黄昏契约 · 日落前暂息纷争 · " + time, blocked, seconds, progress, advancing);
    }
}
