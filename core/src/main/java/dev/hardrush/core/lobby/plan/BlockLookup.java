package dev.hardrush.core.lobby.plan;

/** World access for core searches. Returns ids such as {@code minecraft:stone}; air or unloaded is {@code minecraft:air}. */
@FunctionalInterface
public interface BlockLookup {
    String AIR = "minecraft:air";
    String blockId(int x, int y, int z);
}
