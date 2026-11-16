package dev.hardrush.book;

import dev.hardrush.book.mixin.PlayerListBookSaveAccessor;
import dev.hardrush.player.PlayerProfiles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.TagValueOutput;

/**
 * Acknowledges a book only once the store its holder is restored from keeps it: a lobby body's sealed suspension,
 * otherwise the vanilla player file. Native player saving swallows IO errors, so that file is read back exactly.
 */
public final class BookInventorySaver implements WrittenBookPort.InventorySaver {
    private static final long MAX_NBT_BYTES = 8L * 1_024 * 1_024;
    private final MinecraftServer server;
    private final Supplier<Optional<PlayerProfiles>> profiles;

    public BookInventorySaver(MinecraftServer server) { this(server, () -> PlayerProfiles.of(server)); }

    /** {@code profiles} is the service that owns lobby bodies; a fixture may supply its own uninstalled one. */
    public BookInventorySaver(MinecraftServer server, Supplier<Optional<PlayerProfiles>> profiles) {
        this.server = Objects.requireNonNull(server); this.profiles = Objects.requireNonNull(profiles);
    }

    @Override public CompletionStage<Boolean> save(ServerPlayer player, int slot, ItemStack delivered) {
        Objects.requireNonNull(player); Objects.requireNonNull(delivered);
        if (!server.isSameThread() || player.level().getServer() != server) return CompletableFuture.completedFuture(false);
        // A camp body gets back its sealed suspension on reconnect or entry, never this vanilla file.
        var owner = profiles.get().filter(service -> service.keepsSuspended(player));
        if (owner.isPresent()) return owner.get().sealLobbyDelivery(player, slot, delivered);
        if (!PlayerProfiles.allowVanillaSave(player)) return CompletableFuture.completedFuture(false);
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
