package dev.hardrush.admin;

import dev.hardrush.core.admin.AdminText;
import dev.hardrush.core.config.GameMode;
import dev.hardrush.core.hunt.LateShelter;
import dev.hardrush.core.record.RoundHistory;
import dev.hardrush.core.round.MonsterCoins;
import dev.hardrush.core.round.Participant;
import dev.hardrush.core.round.ParticipantRole;
import dev.hardrush.core.round.RoundPhase;
import dev.hardrush.core.round.RoundResult;
import dev.hardrush.core.round.RoundSnapshot;
import dev.hardrush.dialog.DialogClicks;
import dev.hardrush.notice.Notices;
import dev.hardrush.round.RoundSession;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ClientboundShowDialogPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dialog.ActionButton;
import net.minecraft.server.dialog.CommonButtonData;
import net.minecraft.server.dialog.CommonDialogData;
import net.minecraft.server.dialog.DialogAction;
import net.minecraft.server.dialog.Input;
import net.minecraft.server.dialog.MultiActionDialog;
import net.minecraft.server.dialog.action.CustomAll;
import net.minecraft.server.dialog.body.PlainMessage;
import net.minecraft.server.dialog.input.NumberRangeInput;
import net.minecraft.server.dialog.input.TextInput;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;

/**
 * The operator panel (docs/plan/管理员命令与面板.md 3): native dialogs a vanilla client opens with {@code /hardrush admin}.
 * Every button is a request, never a capability: it runs the matching {@link AdminCommands} operation with the
 * clicking player's own command source, which re-checks permission and state, and the panel reopens once the outcome
 * is known. Outcomes go to chat like the commands'.
 */
public final class AdminPanel {
    private static final String ACTIONS = "admin";
    private static final int WIDTH = 300;
    private static final int BUTTON = 148;
    private static final int MAX_PLAYERS = 60;
    private static final int MAX_RECORDS = 10;
    private static AdminCommands commands;
    private static Function<MinecraftServer, RoundSession> sessions;

    static { DialogClicks.registerWithPayload(ACTIONS, AdminPanel::clicked); }

    private AdminPanel() {}

    public static void install(AdminCommands adminCommands, Function<MinecraftServer, RoundSession> roundSessions) {
        commands = adminCommands; sessions = roundSessions;
    }

    /** Opens the main panel for an operator of level 3 or more; false when the panel cannot serve this player. */
    public static boolean open(ServerPlayer player) {
        if (!admin(player) || session(player) == null || session(player).snapshot().isEmpty()) return false;
        main(player);
        return true;
    }

    // ---- Dialogs --------------------------------------------------------------------------------------------------

    private static void main(ServerPlayer player) {
        var session = session(player);
        var round = session == null ? null : session.snapshot().orElse(null);
        if (round == null) { clear(player); return; }
        send(player, mainDialog(round, session.view().orElse(round), owner(player)));
    }

    /** Pure builders below: the dialogs for a given state, so a test can encode each one as a client would receive it. */
    public static MultiActionDialog mainDialog(RoundSnapshot round, RoundSnapshot view, boolean owner) {
        var lines = new ArrayList<String>();
        lines.add("第 " + round.sequence() + " 轮 · " + phase(round.phase()) + " · "
                + (round.rules().mode() == GameMode.HARDCORE ? "极限挑战" : "普通模式"));
        if (timed(round.phase())) lines.add("阶段剩余 " + Notices.secondsCeil(view.remainingPhaseNanos()) + " 秒");
        if (round.phase() == RoundPhase.RUNNING || round.phase() == RoundPhase.RESCUE) {
            lines.add("用时 " + Notices.clock(view.challengeMillis()) + (round.adminPaused() ? " · 已暂停（计时冻结）" : ""));
        }
        lines.add("成绩：" + (round.timingUncertain() ? "有待管理员核实" : "有效"));
        lines.add("操作结果会发到聊天栏。");
        var buttons = new ArrayList<ActionButton>();
        boolean live = round.phase() == RoundPhase.RUNNING || round.phase() == RoundPhase.RESCUE;
        if (round.phase() == RoundPhase.WAITING || round.phase() == RoundPhase.COUNTDOWN) buttons.add(button("立即开局", "start"));
        if (round.phase() == RoundPhase.COUNTDOWN || round.phase() == RoundPhase.SETTLING || round.phase() == RoundPhase.CELEBRATING) {
            buttons.add(button("跳过等待", "skip"));
        }
        if (live) buttons.add(round.adminPaused() ? button("继续比赛", "resume") : button("暂停比赛", "pause"));
        if (timed(round.phase())) buttons.add(button("设定剩余时间…", "timer"));
        if (!round.participants().isEmpty()) buttons.add(button("玩家…", "players"));
        if (owner) buttons.add(button("复核成绩…", "records"));
        buttons.add(button("刷新", "main"));
        return dialog("HardRush 管理面板", lines, List.of(reasonInput()), buttons);
    }

    private static void timerDialog(ServerPlayer player) {
        var round = snapshot(player);
        if (round != null) send(player, timerDialog(round)); else clear(player);
    }

    public static MultiActionDialog timerDialog(RoundSnapshot round) {
        var seconds = new Input("seconds", new NumberRangeInput(WIDTH, Component.literal("剩余秒数"), "options.generic_value",
                new NumberRangeInput.RangeInfo(0, 600, Optional.of(10f), Optional.of(1f))));
        return dialog("设定剩余时间", List.of("当前阶段：" + phase(round.phase()),
                        "设为 0 秒：倒计时立即开局，展示立即结束，救场立即失败。",
                        round.phase() == RoundPhase.RESCUE ? "调整救场时间会把本轮成绩标记为有待核实。" : "不影响本轮成绩。"),
                List.of(seconds, reasonInput()), List.of(button("确定", "timer-set"), button("返回", "main")));
    }

    private static void playersDialog(ServerPlayer player) {
        var round = snapshot(player);
        if (round == null) { clear(player); return; }
        var server = player.level().getServer();
        send(player, playersDialog(round, p -> server.getPlayerList().getPlayer(p.id()) != null));
    }

    public static MultiActionDialog playersDialog(RoundSnapshot round, java.util.function.Predicate<Participant> online) {
        var buttons = new ArrayList<ActionButton>();
        round.participants().values().stream().limit(MAX_PLAYERS).forEach(p -> buttons.add(
                button(p.name() + " · " + role(p) + (online.test(p) ? "" : "（离线）"), "player/" + p.id())));
        buttons.add(button("返回", "main"));
        return dialog("本轮玩家", List.of("共 " + round.participants().size() + " 人" + (round.participants().size() > MAX_PLAYERS
                ? "，只列出前 " + MAX_PLAYERS + " 人；其余请用命令" : "")), List.of(), buttons);
    }

    private static void playerDialog(ServerPlayer player, UUID id) {
        var session = session(player);
        var round = snapshot(player);
        if (round == null) { clear(player); return; }
        var p = round.participants().get(id);
        if (p == null) { playersDialog(player); return; }
        var body = player.level().getServer().getPlayerList().getPlayer(id);
        send(player, playerDialog(round, session.view().orElse(round), p, body != null,
                body != null && dev.hardrush.lobby.RevivalAltar.blessed(body)));
    }

    public static MultiActionDialog playerDialog(RoundSnapshot round, RoundSnapshot view, Participant p, boolean online, boolean blessed) {
        UUID id = p.id();
        var live = view.participants().getOrDefault(id, p);
        boolean racing = round.phase() == RoundPhase.RUNNING || round.phase() == RoundPhase.RESCUE;
        boolean hardcore = round.rules().mode() == GameMode.HARDCORE;
        boolean coins = MonsterCoins.enabled(round.rules());
        var lines = new ArrayList<String>();
        lines.add(p.name() + " · " + role(p) + (online ? "" : " · 离线") + (p.lifeSequence() > 0 ? " · 第 " + p.lifeSequence() + " 条命" : ""));
        if (hardcore) lines.add("复生图腾 " + live.revivalCharges() + " 个");
        if (p.role().isMonster()) {
            if (coins) lines.add("怪物币 " + live.wallet().coins() + " 枚");
            lines.add("出击冷却 " + Notices.secondsCeil(live.monsterCooldownNanos()) + " 秒 · 击败锁定 " + live.huntLocks().size() + " 个");
        }
        long shelter = LateShelter.remainingNanos(round.rules(), live);
        if (shelter > 0) lines.add("初入荒野还剩 " + LateShelter.clock(shelter));
        if (blessed) lines.add("身上有祭坛的图腾之力");
        String at = "/" + id;
        var buttons = new ArrayList<ActionButton>();
        boolean adjustable = (p.role().isMonster() || p.role().isActiveHuman()) && p.lifeSequence() > 0 && p.joinedAtChallengeNanos() >= 0;
        if (racing && hardcore && adjustable) {
            buttons.add(button("发图腾", "do/totem-give" + at));
            if (live.revivalCharges() > 0) buttons.add(button("扣图腾", "do/totem-take" + at));
        }
        if (racing && coins && p.role().isMonster()) {
            buttons.add(button("发怪物币", "do/coins-give" + at));
            if (live.wallet().coins() > 0) buttons.add(button("扣怪物币", "do/coins-take" + at));
        }
        if (racing && p.role().isMonster() && live.monsterCooldownNanos() > 0) buttons.add(button("清出击冷却", "do/cooldown" + at));
        if (racing && p.role().isMonster() && !live.huntLocks().isEmpty()) buttons.add(button("清击败锁定", "do/unlock" + at));
        if (racing && p.role().isActiveHuman() && (shelter > 0 || blessed)) buttons.add(button("结束庇护", "do/shelter" + at));
        if (online && p.role() == ParticipantRole.LOBBY) buttons.add(button("送进集结区", "do/pull" + at));
        if (!p.role().isExcluded() && round.result() == null && round.phase() != RoundPhase.PREPARING
                && round.phase() != RoundPhase.RECOVERY_REQUIRED) buttons.add(button("取消资格…", "dq" + at));
        buttons.add(button("刷新", "player" + at));
        buttons.add(button("返回", "players"));
        var count = new Input("count", new NumberRangeInput(WIDTH, Component.literal("数量"), "options.generic_value",
                new NumberRangeInput.RangeInfo(1, 64, Optional.of(1f), Optional.of(1f))));
        return dialog(p.name(), lines, List.of(count, reasonInput()), buttons);
    }

    private static void disqualifyDialog(ServerPlayer player, UUID id) {
        var round = snapshot(player);
        var p = round == null ? null : round.participants().get(id);
        if (p == null) { main(player); return; }
        send(player, dialog("取消资格", List.of("确定取消 " + p.name() + " 本轮的参赛资格吗？", "下轮自动恢复。本轮成绩会标记为有待核实。"),
                List.of(reasonInput()), List.of(button("确定取消资格", "dq-yes/" + id), button("返回", "player/" + id))));
    }

    private static void recordsDialog(ServerPlayer player) {
        var session = session(player);
        if (session == null || !owner(player)) { clear(player); return; }
        session.history(50, 0).whenComplete((rows, error) -> {
            if (!player.connection.isAcceptingMessages()) return;
            if (error != null) { player.sendSystemMessage(Component.literal("历史查询失败，请稍后再试。")); clear(player); return; }
            var pending = rows.stream().filter(r -> r.result().kind() == RoundResult.Kind.COMPLETED && r.result().winner() != null
                    && !r.leaderboardEligible()).limit(MAX_RECORDS).toList();
            var buttons = new ArrayList<ActionButton>();
            for (RoundHistory r : pending) {
                buttons.add(button("第 " + r.sequence() + " 轮 · 用时 " + Notices.clock(r.challengeMillis())
                        + (r.trustedWinner() ? "" : " · 冠军待核"), "verify/" + r.roundId()));
            }
            buttons.add(button("返回", "main"));
            send(player, dialog("复核成绩", List.of(pending.isEmpty() ? "最近没有等待复核的通关成绩。"
                            : "点一下即复核，成绩进入排行榜。", "冠军无法核实的轮次只能用 /hardrush admin correct 修正。"),
                    List.of(reasonInput()), buttons));
        });
    }

    // ---- Clicks ---------------------------------------------------------------------------------------------------

    private static void clicked(ServerPlayer player, String action, Optional<Tag> payload) {
        // The client waits for every click's answer: anything that does not reopen a dialog closes it.
        if (commands == null || !admin(player)) { clear(player); return; }
        var source = player.createCommandSourceStack();
        var values = payload.filter(CompoundTag.class::isInstance).map(CompoundTag.class::cast).orElseGet(CompoundTag::new);
        String reason = reason(values);
        int slash = action.indexOf('/');
        String verb = slash < 0 ? action : action.substring(0, slash);
        String rest = slash < 0 ? "" : action.substring(slash + 1);
        Runnable back = () -> main(player);
        switch (verb) {
            case "close" -> clear(player);
            case "main" -> main(player);
            case "players" -> playersDialog(player);
            case "timer" -> timerDialog(player);
            case "records" -> recordsDialog(player);
            case "start" -> commands.current(source, dev.hardrush.core.admin.AdminAction.START, reason, back);
            case "skip" -> commands.skip(source, reason, back);
            case "pause" -> commands.pause(source, true, reason, back);
            case "resume" -> commands.pause(source, false, reason, back);
            case "timer-set" -> commands.timer(source, (int) number(values, "seconds", 0, 600), reason, back);
            case "player" -> uuid(rest).ifPresentOrElse(id -> playerDialog(player, id), back);
            case "dq" -> uuid(rest).ifPresentOrElse(id -> disqualifyDialog(player, id), back);
            case "dq-yes" -> uuid(rest).ifPresentOrElse(id -> dev.hardrush.participation.ParticipationControl.administrativeExit(
                    source, id.toString(), reason, ParticipantRole.DISQUALIFIED, () -> playerDialog(player, id)), back);
            case "verify" -> uuid(rest).ifPresentOrElse(id -> commands.revise(source, id.toString(), 0, "none", reason, true,
                    () -> recordsDialog(player)), back);
            case "do" -> perform(player, source, rest, values, reason);
            default -> clear(player);
        }
    }

    private static void perform(ServerPlayer player, CommandSourceStack source, String rest, CompoundTag values, String reason) {
        int slash = rest.indexOf('/');
        var id = slash < 0 ? Optional.<UUID>empty() : uuid(rest.substring(slash + 1));
        if (id.isEmpty()) { main(player); return; }
        String target = id.get().toString();
        Runnable refresh = () -> playerDialog(player, id.get());
        int count = (int) number(values, "count", 1, 64);
        switch (rest.substring(0, slash)) {
            case "totem-give" -> commands.adjust(source, target, AdminCommands.Item.TOTEM, count, reason, refresh);
            case "totem-take" -> commands.adjust(source, target, AdminCommands.Item.TOTEM, -count, reason, refresh);
            case "coins-give" -> commands.adjust(source, target, AdminCommands.Item.COINS, count, reason, refresh);
            case "coins-take" -> commands.adjust(source, target, AdminCommands.Item.COINS, -count, reason, refresh);
            case "cooldown" -> commands.cooldown(source, target, 0, reason, refresh);
            case "unlock" -> commands.unlock(source, target, null, reason, refresh);
            case "shelter" -> commands.shelter(source, target, reason, refresh);
            case "pull" -> {
                var body = player.level().getServer().getPlayerList().getPlayer(id.get());
                if (body != null) commands.pull(source, body.getScoreboardName());
                refresh.run();
            }
            default -> main(player);
        }
    }

    // ---- Helpers --------------------------------------------------------------------------------------------------

    private static MultiActionDialog dialog(String title, List<String> lines, List<Input> inputs, List<ActionButton> buttons) {
        var body = Component.literal(String.join("\n", lines));
        var common = new CommonDialogData(Component.literal(title), Optional.of(Component.literal("HardRush 管理")), true, false,
                DialogAction.WAIT_FOR_RESPONSE, List.of(new PlainMessage(body, WIDTH)), inputs);
        // Every button waits for an answer, so closing is a request too: the server answers with a clear.
        var close = button("关闭", "close");
        return new MultiActionDialog(common, buttons, Optional.of(close), 2);
    }

    private static void clear(ServerPlayer player) {
        player.connection.send(net.minecraft.network.protocol.common.ClientboundClearDialogPacket.INSTANCE);
    }

    private static void send(ServerPlayer player, MultiActionDialog dialog) {
        player.connection.send(new ClientboundShowDialogPacket(Holder.direct(dialog)));
    }

    /** Every button sends the dialog's inputs along; the handler reads only the ones it needs. */
    private static ActionButton button(String label, String action) {
        return new ActionButton(new CommonButtonData(Component.literal(label), BUTTON),
                Optional.of(new CustomAll(DialogClicks.id(ACTIONS, action), Optional.empty())));
    }

    private static Input reasonInput() {
        return new Input("reason", new TextInput(WIDTH, Component.literal("原因（可选）"), true, "", AdminText.MAX_REASON_CODE_POINTS,
                Optional.empty()));
    }

    /** Client text: blank means the default reason; anything unsafe is refused later by the operation itself. */
    private static String reason(CompoundTag values) {
        var text = values.getString("reason").orElse("").strip();
        return text.isEmpty() ? AdminCommands.DEFAULT_REASON : text;
    }

    /** A slider value, clamped: the client may send anything. */
    private static long number(CompoundTag values, String key, long min, long max) {
        double value = values.get(key) instanceof NumericTag n ? n.doubleValue() : min;
        if (!Double.isFinite(value)) value = min;
        return Math.max(min, Math.min(max, Math.round(value)));
    }

    private static Optional<UUID> uuid(String text) {
        try { return Optional.of(UUID.fromString(text)); } catch (IllegalArgumentException invalid) { return Optional.empty(); }
    }

    private static RoundSession session(ServerPlayer player) {
        return sessions == null ? null : sessions.apply(player.level().getServer());
    }

    private static RoundSnapshot snapshot(ServerPlayer player) {
        var session = session(player);
        return session == null ? null : session.snapshot().orElse(null);
    }

    private static boolean admin(ServerPlayer player) {
        return Commands.hasPermission(Commands.LEVEL_ADMINS).test(player.createCommandSourceStack());
    }

    private static boolean owner(ServerPlayer player) {
        return Commands.hasPermission(Commands.LEVEL_OWNERS).test(player.createCommandSourceStack());
    }

    private static boolean timed(RoundPhase phase) {
        return phase == RoundPhase.COUNTDOWN || phase == RoundPhase.SETTLING || phase == RoundPhase.CELEBRATING
                || phase == RoundPhase.RESCUE;
    }

    private static String phase(RoundPhase phase) {
        return switch (phase) {
            case PREPARING -> "世界准备中";
            case WAITING -> "等待准备";
            case COUNTDOWN -> "开局倒计时";
            case RUNNING -> "挑战中";
            case RESCUE -> "救场中";
            case SETTLING -> "结算展示";
            case CELEBRATING -> "通关庆祝";
            case ROTATING -> "轮换世界";
            case RECOVERY_REQUIRED -> "恢复隔离";
        };
    }

    private static String role(Participant p) {
        return switch (p.role()) {
            case LOBBY -> p.ready() ? "已准备" : "营地";
            case HUMAN_ACTIVE -> "人类";
            case HUMAN_RESPAWNING -> "人类·重生中";
            case MONSTER_LOBBY -> "怪物·营地";
            case MONSTER_DEPLOYED -> "怪物·出击中";
            case WITHDRAWN -> "已退出";
            case DISQUALIFIED -> "已取消资格";
            default -> p.role().name();
        };
    }
}
