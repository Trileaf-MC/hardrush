package dev.hardrush.p1.book;

import dev.hardrush.p1.book.mixin.PlayerListBookSaveAccessor;
import dev.hardrush.p1.player.P1PlayerProfiles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.TagValueOutput;

/** Native player saving swallows IO errors; read back the exact inventory before acknowledging a book. */
public final class P1BookInventorySaver implements Function<ServerPlayer, CompletionStage<Boolean>> {
    private static final long MAX_NBT_BYTES = 8L * 1_024 * 1_024;
    private final MinecraftServer server;

    public P1BookInventorySaver(MinecraftServer server) { this.server = Objects.requireNonNull(server); }

    @Override public CompletionStage<Boolean> apply(ServerPlayer player) {
        Objects.requireNonNull(player);
        if (!server.isSameThread() || player.level().getServer() != server || !P1PlayerProfiles.allowVanillaSave(player)) {
            return CompletableFuture.completedFuture(false);
        }
        try {
            var output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, player.registryAccess());
            player.saveWithoutId(output);
            var expected = output.buildResult();
            if (expected.getStringOr("hardrush_round_id", "").isEmpty()
                    || expected.getLongOr("hardrush_generation", 0) < 1 || expected.getList("Inventory").isEmpty()) {
                return CompletableFuture.completedFuture(false);
            }
            Path directory = server.getWorldPath(LevelResource.PLAYER_DATA_DIR);
            Path file = directory.resolve(player.getUUID() + ".dat");
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file)) {
                return CompletableFuture.completedFuture(false);
            }
            ((PlayerListBookSaveAccessor) server.getPlayerList()).hardrush$saveBookInventory(player);
            return CompletableFuture.completedFuture(matchesSavedInventory(file, expected));
        } catch (IOException | RuntimeException failure) {
            return CompletableFuture.completedFuture(false);
        }
    }

    public static boolean matchesSavedInventory(Path file, CompoundTag expected) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > MAX_NBT_BYTES) return false;
        var saved = NbtIo.readCompressed(file, NbtAccounter.create(MAX_NBT_BYTES));
        return expected.getList("Inventory").isPresent() && saved.getList("Inventory").equals(expected.getList("Inventory"))
                && !expected.getStringOr("hardrush_round_id", "").isEmpty()
                && saved.getString("hardrush_round_id").equals(expected.getString("hardrush_round_id"))
                && expected.getLongOr("hardrush_generation", 0) > 0
                && saved.getLongOr("hardrush_generation", 0) == expected.getLongOr("hardrush_generation", 0);
    }
}
