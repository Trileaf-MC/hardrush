package dev.hardrush.record;

import dev.hardrush.core.book.HistoryBookComposer;
import dev.hardrush.core.config.GameMode;
import dev.hardrush.core.lobby.LobbyContext;
import dev.hardrush.core.record.RoundHistory;
import dev.hardrush.round.RoundRuntime;
import dev.hardrush.round.RoundSession;
import dev.hardrush.lobby.LobbyMenus;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;

/** Per connection, per round query pages; IO callbacks cannot reopen a closed or replacement menu. */
public final class HistoryLibrary {
    private static final Map<MinecraftServer, HistoryLibrary> SERVICES = new IdentityHashMap<>();
    private final MinecraftServer server;
    private final RoundSession session;
    private final HistoryQueries query;
    private final Map<UUID, View> views = new HashMap<>();
    private static final class View {
        final ServerPlayer owner;
        final net.minecraft.server.network.ServerGamePacketListenerImpl connection;
        final UUID scope;
        net.minecraft.world.inventory.AbstractContainerMenu menu;
        List<LobbyMenus.Button> buttons = List.of(info("正在查询历史"));
        RoundHistory selected;
        int request;
        View(ServerPlayer p, UUID round) { owner=p; connection=p.connection; scope=round; }
    }
    private HistoryLibrary(MinecraftServer server, RoundSession session) { this.server=server; this.session=session; query=new HistoryQueries(session); }
    public static void configure(MinecraftServer server, RoundSession session) { SERVICES.put(server,new HistoryLibrary(server,session)); }
    public static void close(MinecraftServer server) { SERVICES.remove(server); }
    public static void disconnected(ServerPlayer p) { var s=SERVICES.get(p.level().getServer()); if(s!=null)s.views.remove(p.getUUID()); }
    public static void tick(MinecraftServer server) {
        var s=SERVICES.get(server); if(s!=null)s.views.values().removeIf(v->!s.current(v));
    }
    public static boolean open(ServerPlayer p) {
        var s=SERVICES.get(p.level().getServer()); if(s==null)return false;
        var view=new View(p,s.session.snapshot().orElseThrow().roundId()); s.views.put(p.getUUID(),view);
        if(!LobbyMenus.open(p,LobbyContext.HISTORY)) { s.views.remove(p.getUUID(),view); return false; }
        view.menu=p.containerMenu;
        s.recent(view,1); return true;
    }
    public static List<LobbyMenus.Button> buttons(ServerPlayer p) {
        var s=SERVICES.get(p.level().getServer()); var v=s==null?null:s.views.get(p.getUUID());
        if(v==null||v.owner!=p||v.connection!=p.connection)return List.of(info("请重新右键书屋讲台"));
        var buttons=new ArrayList<>(v.buttons);
        buttons.add(new LobbyMenus.Button(Items.WRITTEN_BOOK,"阅读已领取书册（含背包深处）","library:read",""));
        return List.copyOf(buttons);
    }
    public static void act(ServerPlayer p,String action,String value) {
        var s=SERVICES.get(p.level().getServer()); var v=s==null?null:s.views.get(p.getUUID()); if(v==null||v.owner!=p||v.connection!=p.connection)return;
        try {
            switch(action) {
                case "library:read" -> RoundRuntime.readOwnedBook(p);
                case "library:recent" -> s.recent(v,Integer.parseInt(value));
                case "library:detail" -> s.detail(v,UUID.fromString(value));
                case "library:roster" -> s.roster(v,Integer.parseInt(value));
                case "library:volumes" -> s.volumes(v,Integer.parseInt(value));
                case "library:leaders" -> s.leaders(v,value);
                case "library:book" -> { if(v.selected!=null) RoundRuntime.manualBook(p,v.selected.roundId(),Integer.parseInt(value)); }
                default -> {}
            }
        } catch(IllegalArgumentException invalid) { v.buttons=List.of(info("页面已失效，请重新右键书屋讲台")); }
    }
    private boolean current(View v) {
        return SERVICES.get(server)==this && views.get(v.owner.getUUID())==v && server.getPlayerList().getPlayer(v.owner.getUUID())==v.owner
                && v.owner.connection==v.connection && session.state()==RoundSession.State.OPEN
                && v.owner.containerMenu==v.menu && LobbyMenus.isMenu(v.owner)
                && session.snapshot().filter(r->r.roundId().equals(v.scope)).isPresent();
    }
    private <T> void load(View v,CompletableFuture<T> future,java.util.function.Function<T,List<LobbyMenus.Button>> show) {
        int token=++v.request; v.buttons=List.of(info("正在查询历史"));
        future.whenComplete((rows,error)-> {
            if(!current(v)||v.request!=token)return;
            try {
                v.buttons=error==null ? List.copyOf(show.apply(rows)) : failure();
            } catch(RuntimeException invalid) { v.buttons=failure(); }
        });
    }
    private static List<LobbyMenus.Button> failure() { return List.of(info("历史服务暂不可查询，请稍后重试；并非空记录"),button("重试轮次列表","recent","1")); }
    private void recent(View v,int page) {
        load(v,query.recent(page),rows->{
            var out=new ArrayList<LobbyMenus.Button>(); out.add(info("已结算轮次 · 第 "+page+" 页"));
            out.add(button("普通模式 · 当前规则榜","leaders","normal")); out.add(button("极限模式 · 当前规则榜","leaders","hardcore"));
            if(rows.isEmpty())out.add(info("暂无已结算历史"));
            rows.stream().limit(HistoryQueries.PAGE_SIZE).forEach(r->out.add(button(HistoryQueries.line(r),"detail",r.roundId().toString())));
            if(page>1)out.add(button("上一批轮次","recent",Integer.toString(page-1)));
            if(rows.size()>HistoryQueries.PAGE_SIZE && page<10000)out.add(button("下一批轮次","recent",Integer.toString(page+1)));
            return out;
        });
    }
    private void detail(View v,UUID id) {
        load(v,query.resolve(id.toString()),found->{
            var out=new ArrayList<LobbyMenus.Button>();
            if(found.isEmpty())out.add(info("该轮尚未结算或不存在；未结算种子不公开"));
            else {
                v.selected=found.orElseThrow(); HistoryQueries.details(v.selected).forEach(t->out.add(info(t)));
                out.add(button("参赛名单","roster","1")); out.add(button("本轮规则榜（模式／版本／数值分榜）","leaders",v.selected.rulesFingerprint()));
                out.add(button("领取主册（卷1）","book","1")); out.add(button("选择分卷","volumes","1"));
            }
            out.add(button("返回轮次列表","recent","1")); return out;
        });
    }
    private void roster(View v,int page) {
        if(v.selected==null)return;
        load(v,query.participants(v.selected.roundId(),page),rows->{
            var out=new ArrayList<LobbyMenus.Button>(); out.add(info("第 "+v.selected.sequence()+" 轮参赛名单 · 第 "+page+" 页"));
            rows.stream().limit(HistoryQueries.PAGE_SIZE).forEach(p->out.add(info(HistoryQueries.participant(p))));
            if(rows.isEmpty())out.add(info("本页无参赛记录"));
            if(page>1)out.add(button("上一批名单","roster",Integer.toString(page-1)));
            if(rows.size()>HistoryQueries.PAGE_SIZE && page<10000)out.add(button("下一批名单","roster",Integer.toString(page+1)));
            out.add(button("返回本轮详情","detail",v.selected.roundId().toString())); return out;
        });
    }
    private void volumes(View v,int page) {
        if(v.selected==null||page<1||page>500)return;
        load(v,session.participantCount(v.selected.roundId()),count->{
            int total=HistoryBookComposer.volumeCount(count); var out=new ArrayList<LobbyMenus.Button>();
            out.add(info("第 "+v.selected.sequence()+" 轮 · 共 "+total+" 卷 · 满包请空出格位后重试所选卷"));
            int first=(page-1)*20+1; for(int n=first;n<=Math.min(total,first+19);n++)out.add(button("领取"+(n==1?"主册":"分卷")+" · 卷 "+n+"/"+total,"book",Integer.toString(n)));
            if(page>1)out.add(button("上一批分卷","volumes",Integer.toString(page-1)));
            if(first+19<total)out.add(button("下一批分卷","volumes",Integer.toString(page+1)));
            out.add(button("返回本轮详情","detail",v.selected.roundId().toString())); return out;
        });
    }
    private void leaders(View v,String input) {
        var rules=session.snapshot().orElseThrow().rules();
        String fingerprint=input.equals("normal")?rules.withMode(GameMode.NORMAL).fingerprint():input.equals("hardcore")?rules.withMode(GameMode.HARDCORE).fingerprint():input;
        load(v,query.leaders(fingerprint),rows->{
            var out=new ArrayList<LobbyMenus.Button>(); out.add(info("规则分榜 · "+fingerprint));
            rows.forEach(r->out.add(button(HistoryQueries.leader(r),"detail",r.round().roundId().toString())));
            if(rows.isEmpty())out.add(info("此模式／规则暂无已核验通关成绩"));
            out.add(button("返回轮次列表","recent","1")); return out;
        });
    }
    private static LobbyMenus.Button info(String label) { return new LobbyMenus.Button(Items.PAPER,label,"info",""); }
    private static LobbyMenus.Button button(String label,String action,String value) { return new LobbyMenus.Button(Items.BOOK,label,"library:"+action,value); }
}
