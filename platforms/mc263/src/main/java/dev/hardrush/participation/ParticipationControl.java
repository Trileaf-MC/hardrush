package dev.hardrush.participation;

import com.mojang.brigadier.arguments.StringArgumentType;
import dev.hardrush.core.lobby.LobbyContext;
import dev.hardrush.core.round.Participant;
import dev.hardrush.core.round.ParticipantRole;
import dev.hardrush.core.round.RoundPhase;
import dev.hardrush.round.RoundSession;
import dev.hardrush.player.PlayerProfiles;
import dev.hardrush.lobby.LobbyMenus;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;

/** Native shortcut, command and confirmation adapters; every exit uses the session's audited durable API. */
public final class ParticipationControl {
    private record Binding(RoundSession session, PlayerProfiles profiles) {}
    private static final Map<MinecraftServer, Binding> BINDINGS = new IdentityHashMap<>();
    private ParticipationControl() {}

    public static void configure(MinecraftServer server, RoundSession session, PlayerProfiles profiles) {
        BINDINGS.put(server, new Binding(session, profiles));
    }
    public static void close(MinecraftServer server) { BINDINGS.remove(server); }

    public static void registerCommands() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> {
            var admin = Commands.literal("admin").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS));
            for (var action : List.of("disqualify", "withdraw")) {
                admin.then(Commands.literal(action).requires(Commands.hasPermission(Commands.LEVEL_ADMINS))
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests((c, builder) -> {
                                    var b = BINDINGS.get(c.getSource().getServer());
                                    if (b != null) b.session.snapshot().ifPresent(r -> r.participants().values().stream()
                                            .filter(p -> p.role() != ParticipantRole.DISQUALIFIED)
                                            .map(Participant::name).filter(n -> n.toLowerCase(java.util.Locale.ROOT)
                                                    .startsWith(builder.getRemainingLowerCase())).forEach(builder::suggest));
                                    return builder.buildFuture();
                                })
                                .then(Commands.argument("reason", StringArgumentType.greedyString())
                                        .executes(c -> administrativeExit(c.getSource(), StringArgumentType.getString(c, "player"),
                                                StringArgumentType.getString(c, "reason"), action.equals("disqualify")
                                                        ? ParticipantRole.DISQUALIFIED : ParticipantRole.WITHDRAWN)))));
            }
            dispatcher.register(Commands.literal("hardrush").then(admin)
                    .then(Commands.literal("spectate").executes(c -> {
                        var player = c.getSource().getPlayerOrException();
                        if (!LobbyMenus.open(player, LobbyContext.WITHDRAWAL))
                            c.getSource().sendFailure(Component.literal("当前不能退出：请等待身份处理完成，或本轮已退出／结算。"));
                        return 1;
                    })));
        });
    }

    private static int administrativeExit(CommandSourceStack source, String input, String reason, ParticipantRole destination) {
        if (!Commands.hasPermission(Commands.LEVEL_ADMINS).test(source)) return 0;
        var b = BINDINGS.get(source.getServer());
        var round = b == null ? null : b.session.snapshot().orElse(null);
        if (round == null) { source.sendFailure(Component.literal("正式轮次尚未就绪。")); return 0; }
        var matches = round.participants().values().stream()
                .filter(p -> p.id().toString().equalsIgnoreCase(input) || p.name().equalsIgnoreCase(input)).toList();
        if (matches.size() != 1) { source.sendFailure(Component.literal("玩家不存在或名称不唯一；可填写本轮玩家 UUID（支持离线玩家）。")); return 0; }
        var p = matches.getFirst();
        String actor = source.getEntity() == null ? "console:" + source.getTextName() : "entity:" + source.getEntity().getUUID();
        b.session.requestParticipationExit(round.roundId(), p.id(), p.role(), p.lifeSequence(), p.monsterDeploymentSequence(),
                destination, actor, reason, reply -> {
                    if (reply.success()) source.sendSuccess(() -> Component.literal(p.name() + "：" + reply.message()), true);
                    else source.sendFailure(Component.literal(reply.message()));
                });
        return 1;
    }

    public static boolean canWithdraw(ServerPlayer player) {
        var b = BINDINGS.get(player.level().getServer());
        var round = b == null ? null : b.session.snapshot().orElse(null);
        var p = round == null ? null : round.participants().get(player.getUUID());
        return p != null && !p.role().isExcluded() && p.role() != ParticipantRole.HUMAN_RESPAWNING
                && p.role() != ParticipantRole.TRANSITION_PENDING && player.isAlive()
                && player.level().getServer().getPlayerList().getPlayer(player.getUUID()) == player
                && b.profiles.isReady(player.getUUID(), round) && b.session.state() == RoundSession.State.OPEN
                && !PlayerProfiles.simulationFrozen(player)
                && !pending(player) && !b.session.admissionReserved(player.getUUID())
                && (round.phase() == RoundPhase.WAITING || round.phase() == RoundPhase.COUNTDOWN
                    || round.phase() == RoundPhase.RUNNING || round.phase() == RoundPhase.RESCUE);
    }

    public static List<LobbyMenus.Button> withdrawalButtons(ServerPlayer player) {
        var b = BINDINGS.get(player.level().getServer());
        var p = b.session.snapshot().orElseThrow().participants().get(player.getUUID());
        return List.of(new LobbyMenus.Button(Items.PAPER, "退出本轮后不能重新加入、追猎或复生；下轮恢复资格", "info", ""),
                new LobbyMenus.Button(Items.CHEST, "比赛物资封存，观战身体清空；不会发放死亡或击杀奖励", "info", ""),
                new LobbyMenus.Button(Items.EMERALD, "确认退出本轮并观战", "withdraw-confirm", token(p)),
                new LobbyMenus.Button(Items.BARRIER, "取消，继续游戏", "withdraw-cancel", ""));
    }

    public static void confirmWithdrawal(ServerPlayer player, UUID expectedRound, String token) {
        if (!canWithdraw(player)) return;
        var b = BINDINGS.get(player.level().getServer());
        var round = b.session.snapshot().orElseThrow();
        var p = round.participants().get(player.getUUID());
        if (!round.roundId().equals(expectedRound) || !token(p).equals(token)) return;
        b.session.requestParticipationExit(expectedRound, p.id(), p.role(), p.lifeSequence(), p.monsterDeploymentSequence(),
                ParticipantRole.WITHDRAWN, "player:" + p.id(), "玩家主动确认退出本轮并观战", reply -> {
                    player.sendSystemMessage(Component.literal(reply.message()));
                    if (reply.success() && player.containerMenu != player.inventoryMenu) player.closeContainer();
                });
    }

    private static String token(Participant p) { return p.role() + ":" + p.lifeSequence() + ":" + p.monsterDeploymentSequence(); }

    /** Uses native keybind components so the client displays its own configured controls. */
    public static Component shortcutHint() {
        return Component.literal("退出本轮并观战：按住 ").append(Component.keybind("key.sneak"))
                .append(" + ").append(Component.keybind("key.swapOffhand")).append(" 打开确认菜单")
                .withStyle(ChatFormatting.GRAY);
    }

    /** Consume only an eligible player's deliberate sneak + swap gesture; opening never commits an exit. */
    public static boolean withdrawalShortcut(ServerPlayer player) {
        if (!player.isShiftKeyDown() || !canWithdraw(player)) return false;
        // A repeated/forged packet must not replace an open menu or release its carried stack.
        if (player.containerMenu == player.inventoryMenu) LobbyMenus.open(player, LobbyContext.WITHDRAWAL);
        player.containerMenu.sendAllDataToRemote();
        return true;
    }

    public static void offerWithdrawal(ServerPlayer player) {
        player.sendSystemMessage(Component.literal("[退出本轮并观战]").withStyle(style -> style.withColor(ChatFormatting.GRAY)
                .withUnderlined(true).withClickEvent(new ClickEvent.RunCommand("/hardrush spectate"))
                .withHoverEvent(new HoverEvent.ShowText(Component.literal("点击打开确认菜单；退出后本轮不能重新参赛")))));
        player.sendSystemMessage(Component.literal("常驻入口见 TAB · ").append(shortcutHint()));
    }

    public static boolean disabled(MinecraftServer server, UUID player) {
        var b = BINDINGS.get(server);
        return b != null && (b.session.participationExitReserved(player)
                || b.session.snapshot().map(r -> r.participants().get(player)).filter(p -> p.role().isExcluded()).isPresent());
    }

    public static boolean pending(ServerPlayer player) {
        var b = BINDINGS.get(player.level().getServer());
        return b != null && b.session.participationExitReserved(player.getUUID());
    }

    public static void reconcile(MinecraftServer server) {
        var b = BINDINGS.get(server);
        if (b == null) return;
        var round = b.session.snapshot().orElse(null);
        if (round == null) return;
        for (var p : round.participants().values()) {
            var player = server.getPlayerList().getPlayer(p.id());
            if (!p.role().isExcluded() || player == null || !b.profiles.isReady(p.id(), round)) continue;
            if (dev.hardrush.monster.MonsterRoles.isMonster(player)) dev.hardrush.monster.MonsterRoles.clear(player);
            b.profiles.reconcileParticipationExit(player, round);
        }
    }
}
