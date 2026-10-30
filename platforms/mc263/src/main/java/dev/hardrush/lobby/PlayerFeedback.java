package dev.hardrush.lobby;

import dev.hardrush.core.lobby.*;
import dev.hardrush.core.record.RoundResultText;
import dev.hardrush.core.round.*;
import dev.hardrush.round.RoundSession;
import dev.hardrush.player.PlayerProfiles;
import dev.hardrush.world.LobbyWorld;
import dev.hardrush.monster.MonsterRoles;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.server.MinecraftServer;

/** Render after all intent producers, including early-return recovery/celebration ticks. */
public final class PlayerFeedback {
    private static final java.util.Map<MinecraftServer, net.minecraft.server.level.ServerBossEvent> BARS = new java.util.IdentityHashMap<>();
    private PlayerFeedback() {}
    public static void close(MinecraftServer server) { var bar=BARS.remove(server); if(bar!=null)bar.removeAllPlayers(); }
    private static void stage(MinecraftServer server, RoundSnapshot round) {
        var stageBar=BARS.computeIfAbsent(server, ignored->new net.minecraft.server.level.ServerBossEvent(java.util.UUID.randomUUID(), Component.literal("HardRush"),
                net.minecraft.world.BossEvent.BossBarColor.YELLOW, net.minecraft.world.BossEvent.BossBarOverlay.PROGRESS));
        boolean rescue=round.phase()==dev.hardrush.core.round.RoundPhase.RESCUE;
        boolean celebrationPhase=round.phase()==RoundPhase.CELEBRATING;
        boolean settling=round.phase()==dev.hardrush.core.round.RoundPhase.SETTLING;
        boolean displaying=settling||celebrationPhase;
        boolean completed=displaying&&round.result().kind()==RoundResult.Kind.COMPLETED;
        boolean celebrating=celebrationPhase&&completed;
        boolean recovery = round.phase()==RoundPhase.RECOVERY_REQUIRED || dev.hardrush.round.RoundRuntime.sessionState(server)
                .filter(state->state!=RoundSession.State.OPEN).isPresent();
        stageBar.setVisible(recovery||rescue||displaying);
        String terminalText=displaying?RoundResultText.stageLabel(round.result(), celebrating?"通关庆祝 · 胜者跳入龙池／阵亡／离线即结束":"结算展示"):"结算展示";
        stageBar.setName(Component.literal(recovery ? "恢复隔离 · 竞技入口关闭，请联系管理员" : (rescue?"救场 · 祭坛复生／中途门加入":terminalText)
                + " · 剩余 " + (round.remainingPhaseMillis()+999)/1000 + " 秒"));
        long total=rescue?round.rules().combatValues().get("round.rescueMillis").longValue():completed
                ?RoundEngine.successCelebrationMillis(round.rules()):round.rules().failureDisplayMillis();
        stageBar.setProgress(recovery ? 0 : Math.max(0,Math.min(1,(float)round.remainingPhaseMillis()/Math.max(1,total))));
        for(var old:java.util.List.copyOf(stageBar.getPlayers()))if(server.getPlayerList().getPlayer(old.getUUID())!=old)stageBar.removePlayer(old);
        for(var player:server.getPlayerList().getPlayers())stageBar.addPlayer(player);
    }

    public static void tick(MinecraftServer server, RoundSnapshot r, RoundSession session) {
        var router=LobbyInteractions.get(server).orElse(null); if(router==null)return;
        if(server.getTickCount()%5==0)stage(server,r);
        long now=System.nanoTime()/1_000_000;
        boolean frozen=session.competitionLocked();
        long humans=r.participants().values().stream().filter(p->p.role().isActiveHuman()).count();
        for(var player:server.getPlayerList().getPlayers()) {
            var hints=router.hints(player); var p=r.participants().get(player.getUUID());
            for(var source:java.util.List.of("recovery","profile","rescue","away","celebration","skill","facility"))hints.clear(source);
            if(!LobbyWorld.isLobby(player.level()))hints.clear("assembly");
            if(p==null||p.role()!=ParticipantRole.MONSTER_LOBBY){hints.clear("hunt");hints.clear("hunt-target");}
            if(!dev.hardrush.lobby.LobbyRuntime.channeling(player))hints.clear("return");
            if(frozen) dev.hardrush.lobby.LobbyRuntime.interruptReturn(player,"阶段变化／恢复隔离");
            if(session.state()!=RoundSession.State.OPEN||r.phase()==RoundPhase.RECOVERY_REQUIRED)
                hints.offer("recovery",LobbyHints.Priority.RECOVERY,"恢复隔离 · 竞技入口关闭；可用的规则与历史仍可阅读",now+300);
            else if(PlayerProfiles.simulationFrozen(player) && !dev.hardrush.celebration.CelebrationRuntime.arena(player))
                hints.offer("profile",LobbyHints.Priority.RECOVERY,"正在核验／应用个人档案，请稍候",now+300);
            if(r.phase()==RoundPhase.RESCUE)
                hints.offer("rescue",LobbyHints.Priority.FACILITY,"救场剩余 "+(r.remainingPhaseMillis()+999)/1000+" 秒 · 祭坛复生或中途门参赛恢复挑战",now+300);
            else if(r.phase()==RoundPhase.RUNNING && humans>0 && r.onlineHumans()==0)
                hints.offer("away",LobbyHints.Priority.FACILITY,"人类暂离，挑战计时暂停；资格保留，暂无可追猎目标",now+300);
            if(dev.hardrush.celebration.CelebrationRuntime.arena(player)) {
                String left="剩余 "+(r.remainingPhaseMillis()+999)/1000+" 秒";
                hints.offer("celebration",LobbyHints.Priority.FACILITY,dev.hardrush.celebration.CelebrationRuntime.winner(player)
                        ?"你是本轮胜者 · 跳入龙池结束庆祝 · "+left
                        :player.isSpectator()?"已阵亡旁观 · 庆祝"+left
                        :"胜者 "+dev.hardrush.celebration.CelebrationRuntime.winnerName(server)+" 庆祝中 · 原版生存，死亡转旁观，不计战绩 · "+left,now+300);
            }
            if(MonsterRoles.isMonster(player) && !dev.hardrush.lobby.LobbyRuntime.channeling(player)) {
                var name=player.getMainHandItem().get(net.minecraft.core.component.DataComponents.CUSTOM_NAME);
                if(name!=null)hints.offer("skill",LobbyHints.Priority.FACILITY,name.getString(),now+300);
            }
            var lobby=LobbyWorld.open(server);
            if(lobby.ready()&&LobbyWorld.isLobby(player.level())&&PlayerProfiles.verifiedLobbyBody(player)) {
                for(var facility:LobbyLayout.Facility.values()) {
                    if(player.position().distanceToSqr(lobby.layoutPosition(LobbyLayout.facility(facility)))>16)continue;
                    String text=switch(facility) {
                        case RULES -> "右键规则讲台阅读玩法"; case HISTORY -> "右键书屋 · 轮次／名单／分榜／主册与分卷";
                        case ALTAR -> p!=null&&p.revivalEntitled()?"剩余复生 " + p.revivalCharges() + " 次 · 右键祭坛确认，冷却期间可用":"尚无复生资格 · 击杀在赛人类后获得";
                        case MIDROUND -> "中途门 · 挑战／救场中确认空装加入";
                        case ZOMBIE, SKELETON, CREEPER -> "右键怪物模型选择角色，之后右键目标模型追猎";
                        case PREVIOUS, NEXT -> "右键翻阅本人的追猎庭院";
                    };
                    hints.offer("facility",LobbyHints.Priority.FACILITY,text,now+300); break;
                }
            }
            if(server.getTickCount()%5==0)hints.current(now).ifPresent(text->player.connection.send(new ClientboundSetActionBarTextPacket(Component.literal(text))));
        }
    }
}
