package dev.hardrush.core.combat;

/** First sunrise-to-sunrise grace period. Display thresholds are real seconds at the current clock rate. */
public final class DawnPact {
    public static final long SUNRISE_TICKS = 24_000;
    public static final int WARNING_SECONDS = 180;
    public static final int COMBAT_SECONDS = 60;
    public static final String INTRO = "黎明契约保护你免受来自其他玩家的伤害，契约持续到明天日出";
    public static final String BLOCKED = "黎明契约期间，你不可攻击其他玩家";
    public enum Color { GREEN, YELLOW, RED }
    public record Display(Color color, String text, long seconds, float progress, boolean advancing) {}

    private DawnPact() {}

    public static Display display(long ticks, float partialTick, float clockRate, double ticksPerSecond, boolean paused) {
        double remaining = Math.max(0, SUNRISE_TICKS - ticks - partialTick);
        boolean advancing = !paused && clockRate > 0 && ticksPerSecond > 0;
        long seconds = advancing ? (long)Math.ceil(remaining / (clockRate * ticksPerSecond)) : Long.MAX_VALUE;
        Color color = seconds <= COMBAT_SECONDS ? Color.RED : seconds <= WARNING_SECONDS ? Color.YELLOW : Color.GREEN;
        String time = advancing ? "%02d:%02d".formatted(seconds / 60, seconds % 60) : "昼夜暂停";
        String text = switch (color) {
            case GREEN -> "黎明契约 · 日出前暂息纷争 · " + time;
            case YELLOW -> "黎明契约即将解除 · 距猎杀开始还有 " + time;
            case RED -> "准备作战";
        };
        return new Display(color, text, seconds, (float)Math.clamp(remaining / SUNRISE_TICKS, 0, 1), advancing);
    }
}
