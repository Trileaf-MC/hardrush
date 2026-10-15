package dev.hardrush.p1.world;

import dev.hardrush.core.round.RoundSnapshot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** Read-only ownership verification for a previous process's worlds. Caller proves none are loaded. */
public final class P1ClosedWorlds {
    private P1ClosedWorlds() {}

    public static void verifyRetained(Path root, RoundSnapshot round) throws IOException {
        Path manifest = P1WorldPaths.manifest(root, round.roundId());
        P1WorldPaths.checkAncestors(manifest, false);
        boolean exists = Files.exists(manifest, LinkOption.NOFOLLOW_LINKS);
        for (String dimension : P1WorldManifest.DIMENSIONS) {
            Path world = P1WorldPaths.world(root, round.roundId(), dimension);
            P1WorldPaths.validate(root, round.roundId(), dimension, world);
            if (!exists && Files.exists(world, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("原轮世界存在但所有权清单缺失；保留文件并停止轮换");
            }
        }
        // A crash before manifest creation legitimately leaves no world files at all.
        if (exists) P1WorldManifest.read(root, manifest).requireSnapshot(round);
    }
}
