package dev.hardrush.round;

import java.io.IOException;
import java.nio.file.Files;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Each new round copies the server difficulty; HardRush replaces only vanilla's untouched default, once. */
final class ServerDifficulty {
    private static final Logger LOGGER = LoggerFactory.getLogger("hardrush-round");
    private static final String MARKER = "hardrush/server-difficulty-default";

    private ServerDifficulty() {}

    /** Vanilla ships difficulty=easy; a competitive server starts on hard unless the operator chose otherwise. */
    static void applyFirstRunDefault(MinecraftServer server) {
        var marker = server.getWorldPath(LevelResource.ROOT).resolve(MARKER);
        if (Files.exists(marker)) return;
        if (server instanceof DedicatedServer dedicated && dedicated.getProperties().difficulty.get() == Difficulty.EASY) {
            dedicated.setDifficulty(Difficulty.HARD);
            LOGGER.info("server.properties 仍是原版默认 difficulty=easy，已改为 hard；之后的修改会按原样用于新轮次");
        }
        try {
            Files.createDirectories(marker.getParent());
            Files.writeString(marker, "applied\n");
        } catch (IOException error) {
            LOGGER.warn("无法记录服务器难度默认值已应用；下次启动会再次检查", error);
        }
    }

    /** Null keeps the stored rule: peaceful has no monsters and cannot hold a round. */
    static dev.hardrush.core.config.Difficulty current(MinecraftServer server) {
        return switch (server.getWorldData().getDifficulty()) {
            case PEACEFUL -> {
                LOGGER.warn("服务器难度为和平，新轮次沿用上一份规则难度");
                yield null;
            }
            case EASY -> dev.hardrush.core.config.Difficulty.EASY;
            case NORMAL -> dev.hardrush.core.config.Difficulty.NORMAL;
            case HARD -> dev.hardrush.core.config.Difficulty.HARD;
        };
    }
}
