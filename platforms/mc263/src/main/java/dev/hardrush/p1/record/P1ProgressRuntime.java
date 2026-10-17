package dev.hardrush.p1.record;

import dev.hardrush.core.record.ProgressWrite;
import dev.hardrush.p1.player.P1PlayerProfiles;
import dev.hardrush.p1.player.mixin.PlayerAdvancementProfileAccessor;
import dev.hardrush.p1.world.RoundWorldRouting;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerAdvancementManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

/** Server-thread capture of fully completed display advancements and first human End presence. */
public final class P1ProgressRuntime {
    public record Context(UUID roundId, long challengeNanos) {
        public Context {
            Objects.requireNonNull(roundId);
            if (challengeNanos < 0) throw new IllegalArgumentException("Negative challenge time");
        }
    }
    @FunctionalInterface public interface Eligibility { Optional<Context> activeRound(ServerPlayer player); }

    private static final class Binding {
        final Eligibility eligibility;
        final Consumer<ProgressWrite> writes;
        final P1ProgressTracker tracker = new P1ProgressTracker();
        ServerAdvancementManager manager;
        P1AdvancementCatalog catalog;
        Binding(Eligibility eligibility, Consumer<ProgressWrite> writes) {
            this.eligibility = Objects.requireNonNull(eligibility);
            this.writes = Objects.requireNonNull(writes);
        }
        P1AdvancementCatalog catalog(MinecraftServer server) {
            // The vanilla manager is immutable and replaced when data packs are reloaded.
            if (manager != server.getAdvancements()) {
                manager = server.getAdvancements();
                catalog = P1AdvancementCatalog.from(manager.getAllAdvancements().stream()
                        .map(holder -> new P1AdvancementCatalog.Entry(holder.id().toString(),
                                holder.value().display().isPresent(), holder.value().isRoot())).toList());
            }
            return catalog;
        }
    }
    private static final Map<MinecraftServer, Binding> BINDINGS = new IdentityHashMap<>();
    private P1ProgressRuntime() {}

    public static void configure(MinecraftServer server, Eligibility eligibility, Consumer<ProgressWrite> writes) {
        BINDINGS.put(Objects.requireNonNull(server), new Binding(eligibility, writes));
    }
    public static void unconfigure(MinecraftServer server) { BINDINGS.remove(server); }


    /** Called at native award RETURN, after the final required criterion and rewards have completed. */
    public static void advancementAwarded(ServerPlayer player, AdvancementHolder holder, boolean changed) {
        if (changed && holder.value().display().isPresent() && !holder.value().isRoot()
                && player.getAdvancements().getOrStartProgress(holder).isDone()) capture(player);
    }

    /** Called after native dimension trigger processing; lobby/profile moves remain ineligible. */
    public static void dimensionChanged(ServerPlayer player) { capture(player); }

    /** Also detects data-pack set changes and idempotently synchronizes an admitted same-round reconnect. */
    public static void tick(MinecraftServer server) {
        if (!BINDINGS.containsKey(server)) return;
        for (var player : server.getPlayerList().getPlayers()) capture(player);
    }

    private static void capture(ServerPlayer player) {
        var server = player.level().getServer();
        var binding = BINDINGS.get(server);
        if (binding == null || P1PlayerProfiles.isLocked(player)) return;
        var context = binding.eligibility.activeRound(player);
        if (context.isEmpty()) return;
        var current = context.orElseThrow();
        var catalog = binding.catalog(server);
        var completed = new HashSet<String>();
        // Reading the existing map avoids getOrStartProgress allocating recipe/technical progress on every tick.
        var progress = ((PlayerAdvancementProfileAccessor) player.getAdvancements()).hardrush$progress();
        for (var entry : progress.entrySet()) {
            var id = entry.getKey().id().toString();
            if (entry.getValue().isDone() && catalog.ids().contains(id)) completed.add(id);
        }
        boolean inEnd = RoundWorldRouting.semanticDimension(player.level()).equals(Level.END);
        binding.tracker.observe(current.roundId(), player.getUUID(), player.getScoreboardName(), catalog,
                completed, inEnd, current.challengeNanos()).ifPresent(binding.writes);
    }
}
