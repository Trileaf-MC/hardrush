package dev.hardrush.core.lobby.plan;

import static dev.hardrush.core.lobby.plan.Binding.*;

import java.util.*;

/**
 * Every lobby function a pack can bind. A new required function must either be enforced at publish time or
 * declare a {@link Fallback}, so that packs built for older mod versions still install.
 */
public enum LobbyFunction {
    ASSEMBLY("assembly", "集结区", Set.of(ZONE), 1, Integer.MAX_VALUE, true, Fallback.NONE, null),
    COURTYARD("courtyard", "怪物庭院", Set.of(ZONE), 1, Integer.MAX_VALUE, true, Fallback.NONE, null),
    ALTAR_PAD("altar_pad", "祭坛踏板", Set.of(ZONE), 1, Integer.MAX_VALUE, true, Fallback.NONE, null),
    ALTAR_LIGHT("altar_light", "祭坛光柱", Set.of(BLOCK), 0, 4, false, Fallback.NONE,
            new BlockRequirement("minecraft:tinted_glass", Map.of())),
    LANDING_CENTRAL("landing_central", "中央落点", Set.of(POINT), 1, 16, true, Fallback.NONE, null),
    LANDING_MONSTER("landing_monster", "庭院落点", Set.of(POINT), 1, 16, true, Fallback.NONE, null),
    /** Optional: without one, altar arrivals use the courtyard landings instead. */
    LANDING_ALTAR("landing_altar", "祭坛落点", Set.of(POINT), 0, 16, false, Fallback.NONE, null),
    /** Ordered by {@code (y, z, x)}; capacity is the number of slots. */
    DISPLAY_SLOT("display_slot", "追猎目标展位", Set.of(POINT), 1, 60, true, Fallback.NONE, null),
    RULES("rules", "规则", Set.of(CLICK), 1, Integer.MAX_VALUE, true, Fallback.LECTERN, null),
    HISTORY("history", "战绩", Set.of(CLICK), 1, Integer.MAX_VALUE, true, Fallback.LECTERN, null),
    ROLE_ZOMBIE("role_zombie", "僵尸展位", Set.of(MODEL, CLICK), 1, Integer.MAX_VALUE, true, Fallback.MODEL, null),
    ROLE_SKELETON("role_skeleton", "骷髅展位", Set.of(MODEL, CLICK), 1, Integer.MAX_VALUE, true, Fallback.MODEL, null),
    ROLE_CREEPER("role_creeper", "苦力怕展位", Set.of(MODEL, CLICK), 1, Integer.MAX_VALUE, true, Fallback.MODEL, null),
    MERCHANT("merchant", "女巫商人", Set.of(MODEL, CLICK), 1, Integer.MAX_VALUE, true, Fallback.MODEL, null),
    VAULT("vault", "试炼宝库", Set.of(BLOCK), 1, Integer.MAX_VALUE, true, Fallback.VAULT,
            new BlockRequirement("minecraft:vault", Map.of("ominous", "false"))),
    OMINOUS_VAULT("ominous_vault", "不祥宝库", Set.of(BLOCK), 1, Integer.MAX_VALUE, true, Fallback.VAULT,
            new BlockRequirement("minecraft:vault", Map.of("ominous", "true"))),
    PAGE_PREV("page_prev", "上一页", Set.of(CLICK), 1, Integer.MAX_VALUE, true, Fallback.SIGN, null),
    PAGE_NEXT("page_next", "下一页", Set.of(CLICK), 1, Integer.MAX_VALUE, true, Fallback.SIGN, null),
    /**
     * A sign block or tagged text display. Any sign kind qualifies, so there is no single
     * {@link BlockRequirement}; the platform checks the block is a sign.
     */
    STATUS("status", "状态牌", Set.of(TEXT), 0, 8, false, Fallback.NONE, null),
    /** Same block rule as {@link #STATUS}. */
    CREDITS("credits", "署名牌", Set.of(TEXT), 0, 8, false, Fallback.NONE, null);

    private static final Map<String, LobbyFunction> BY_ID = new HashMap<>();
    static { for (var f : values()) BY_ID.put(f.id, f); }

    private final String id, displayName;
    private final Set<Binding> bindings;
    private final int min, max;
    private final boolean required;
    private final Fallback fallback;
    private final BlockRequirement block;

    LobbyFunction(String id, String displayName, Set<Binding> bindings, int min, int max, boolean required,
                  Fallback fallback, BlockRequirement block) {
        this.id = id; this.displayName = displayName; this.bindings = Collections.unmodifiableSet(EnumSet.copyOf(bindings));
        this.min = min; this.max = max; this.required = required; this.fallback = fallback; this.block = block;
    }

    public String id() { return id; }
    public String displayName() { return displayName; }
    public Set<Binding> bindings() { return bindings; }
    public int min() { return min; }
    /** {@link Integer#MAX_VALUE} means unbounded. */
    public int max() { return max; }
    public boolean required() { return required; }
    public Fallback fallback() { return fallback; }
    public Optional<BlockRequirement> block() { return Optional.ofNullable(block); }
    /** Required and without a stand-in: a pack missing it cannot be published. */
    public boolean publishEnforced() { return required && fallback == Fallback.NONE; }
    public static Optional<LobbyFunction> byId(String id) { return Optional.ofNullable(BY_ID.get(id)); }
}
