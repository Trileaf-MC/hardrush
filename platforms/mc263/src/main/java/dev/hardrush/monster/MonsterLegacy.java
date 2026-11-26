package dev.hardrush.monster;

import dev.hardrush.combat.CombatOriginHolder;
import dev.hardrush.combat.CombatRuntime;
import dev.hardrush.combat.MonsterCombatRuntime;
import dev.hardrush.combat.MonsterSourceHolder;
import dev.hardrush.core.api.DamagePort.AttackKind;
import dev.hardrush.core.combat.CombatOrigin;
import dev.hardrush.notice.Notices;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.util.random.WeightedList;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.hurtingprojectile.windcharge.AbstractWindCharge;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gamerules.GameRules;

/**
 * What a monster's potions leave behind (docs/plan/女巫药水与药水盲盒.md §5): a creeper's lingering cloud, the slimes
 * of oozing, the silverfish of infested, the gust of wind charged and the cobwebs of weaving. Whatever they do to a
 * human is the monster's own doing, frozen at the sortie that drank them, and the monster is told, since it is back in
 * the camp by then. Monster players die outside vanilla's removal path, so the death effects are triggered here, at the
 * native moment and place.
 */
public final class MonsterLegacy {
    private static final String OWNER = "hardrush.legacy-owner=";
    private static final String KIND = "hardrush.legacy-kind=";
    private static final int HINT_INTERVAL_TICKS = 60;

    /** What is left behind, with how the death message names it and how the owner hears of it. */
    public enum Kind {
        CLOUD("留下的", "药水云"), SLIME("留下的", "史莱姆"), SILVERFISH("身上钻出的", "蠹虫"), GUST("留下的", "风");
        private final String relation;
        private final String noun;
        Kind(String relation, String noun) { this.relation = relation; this.noun = noun; }
        /** "留下的", between the owner's name and the creature's in a death message. */
        public String relation() { return relation; }
        /** "留下的史莱姆", after "你" when the owner is told. */
        String told() { return relation + noun; }
    }

    private record Source(UUID owner, Kind kind) {}
    /** Direct entity of a left-behind origin → its owner and kind; memory only, newest kept. */
    private static final Map<MinecraftServer, Map<UUID, Source>> SOURCES = new java.util.WeakHashMap<>();
    private static final Map<MinecraftServer, Map<String, Integer>> HINTED = new java.util.WeakHashMap<>();

    private MonsterLegacy() {}

    private static Map<UUID, Source> sources(MinecraftServer server) {
        return SOURCES.computeIfAbsent(server, ignored -> new LinkedHashMap<>(64, .75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<UUID, Source> eldest) { return size() > 2048; }
        });
    }

    private static void remember(MinecraftServer server, Optional<CombatOrigin> origin, UUID owner, Kind kind) {
        origin.flatMap(CombatOrigin::directEntityId).ifPresent(direct -> sources(server).put(direct, new Source(owner, kind)));
    }

    /** A creeper's lingering cloud, sealed with its origin: its later poison and the like are the creeper's. */
    static void cloud(ServerPlayer owner, Optional<CombatOrigin> origin) { remember(owner.level().getServer(), origin, owner.getUUID(), Kind.CLOUD); }

    /** At the monster's death, where it fell: its death effects happen now, once. */
    static void died(ServerPlayer player) {
        var level = player.level();
        var model = MonsterRoles.nativeModel(player);
        for (var effect : List.copyOf(player.getActiveEffects())) {
            if (effect.is(MobEffects.WEAVING)) {
                // Native: a player's death always weaves, whatever the griefing rule.
                effect.onMobRemoved(level, player, Entity.RemovalReason.KILLED);
            } else if (effect.is(MobEffects.OOZING) && model != null) {
                ooze(level, player, model);
            } else if (effect.is(MobEffects.WIND_CHARGED) && model != null) {
                gust(level, player, model);
            } else continue;
            player.removeEffect(effect.getEffect());
        }
    }

    /** Native oozing: two medium slimes, fewer where the cramming rule is already full of them. */
    private static void ooze(ServerLevel level, ServerPlayer player, Mob model) {
        int cramming = level.getGameRules().get(GameRules.MAX_ENTITY_CRAMMING);
        int nearby = level.getEntities(EntityTypes.SLIME, player.getBoundingBox().inflate(2.0), slime -> true).size();
        int count = cramming < 1 ? 2 : Mth.clamp(cramming - nearby, 0, 2);
        for (int i = 0; i < count; i++) {
            var slime = EntityTypes.SLIME.create(level, EntitySpawnReason.TRIGGERED);
            if (slime == null) continue;
            slime.setSize(2, true);
            slime.snapTo(player.getX(), player.getY() + 0.5, player.getZ(), level.getRandom().nextFloat() * 360.0F, 0.0F);
            adopt(slime, player.getUUID(), model, Kind.SLIME);
            level.addFreshEntity(slime);
        }
    }

    /**
     * Native wind charged: a burst of gust strength 3–5 at mid-body. It deals no damage, so every human it may throw is
     * remembered as hit by the monster, as vanilla remembers a punch: a fall in the next seconds is its kill.
     */
    private static void gust(ServerLevel level, ServerPlayer player, Mob model) {
        double x = player.getX(), y = player.getY() + player.getBbHeight() / 2.0, z = player.getZ();
        float strength = 3.0F + player.getRandom().nextFloat() * 2.0F;
        var origin = CombatRuntime.originOf(model).flatMap(parent -> child(parent, parent.kind(), UUID.randomUUID()));
        remember(level.getServer(), origin, player.getUUID(), Kind.GUST);
        origin.ifPresent(credit -> {
            for (var human : level.getEntitiesOfClass(ServerPlayer.class, player.getBoundingBox().inflate(strength * 2.0))) {
                if (human != player && !MonsterRoles.isMonster(human) && MonsterRoles.legalTarget(player.getUUID(), level, human)) {
                    CombatRuntime.creditOrigin(level, human, credit);
                    // A harmless entry, as a punch would leave: a fall that follows reads "while escaping" the monster.
                    human.getCombatTracker().recordDamage(level.damageSources().mobAttack(model), 0);
                }
            }
        });
        model.snapTo(player.position());
        level.explode(model, null, AbstractWindCharge.EXPLOSION_DAMAGE_CALCULATOR, x, y, z, strength, false,
                Level.ExplosionInteraction.TRIGGER, ParticleTypes.GUST_EMITTER_SMALL, ParticleTypes.GUST_EMITTER_LARGE,
                WeightedList.of(), SoundEvents.BREEZE_WIND_CHARGE_BURST);
    }

    /** Silverfish of infested crawling out of a hurt monster player; the native spawn is otherwise untouched. */
    public static void infested(Entity hurt, Entity silverfish) {
        if (!(hurt instanceof ServerPlayer player) || !(silverfish instanceof Mob mob) || !MonsterRoles.isMonster(player)) return;
        var model = MonsterRoles.nativeModel(player);
        if (model != null) adopt(mob, player.getUUID(), model, Kind.SILVERFISH);
    }

    /** {@code mob} acts for the monster that left it: frozen like the monster's own body, named after it. */
    private static void adopt(Mob mob, UUID owner, Mob model, Kind kind) {
        mob.entityTags().add(OWNER + owner);
        mob.entityTags().add(KIND + kind.name());
        if (mob instanceof MonsterSourceHolder holder) MonsterCombatRuntime.source(model).ifPresent(holder::hardrush$stampMonsterSource);
        if (mob instanceof CombatOriginHolder holder && !holder.hardrush$combatOriginSealed()) {
            var origin = CombatRuntime.originOf(model).flatMap(parent -> child(parent, AttackKind.SUMMON, mob.getUUID()));
            holder.hardrush$sealCombatOrigin(origin);
            if (mob.level() instanceof ServerLevel level) remember(level.getServer(), origin, owner, kind);
        }
    }

    /** A slime splitting keeps its owner: the smaller ones are left behind by the same monster. */
    public static void inherit(Entity source, Mob child) {
        var owner = owner(source);
        var kind = kind(source);
        if (owner == null || kind == null) return;
        child.entityTags().add(OWNER + owner);
        child.entityTags().add(KIND + kind.name());
        if (child instanceof MonsterSourceHolder holder) MonsterCombatRuntime.source(source).ifPresent(holder::hardrush$stampMonsterSource);
        if (child instanceof CombatOriginHolder holder && !holder.hardrush$combatOriginSealed()) {
            var origin = CombatRuntime.originOf(source).flatMap(parent -> child(parent, AttackKind.SUMMON, child.getUUID()));
            holder.hardrush$sealCombatOrigin(origin);
            if (child.level() instanceof ServerLevel level) remember(level.getServer(), origin, owner, kind);
        }
    }

    private static Optional<CombatOrigin> child(CombatOrigin parent, AttackKind kind, UUID direct) {
        try { return Optional.of(parent.child(UUID.randomUUID(), kind, Optional.of(direct))); }
        catch (IllegalArgumentException exhaustedChain) { return Optional.empty(); }
    }

    public static UUID owner(Entity entity) {
        if (entity == null) return null;
        for (String tag : entity.entityTags()) if (tag.startsWith(OWNER)) {
            try { return UUID.fromString(tag.substring(OWNER.length())); } catch (IllegalArgumentException malformed) { return null; }
        }
        return null;
    }

    public static Kind kind(Entity entity) {
        if (entity == null) return null;
        for (String tag : entity.entityTags()) if (tag.startsWith(KIND)) {
            try { return Kind.valueOf(tag.substring(KIND.length())); } catch (IllegalArgumentException malformed) { return null; }
        }
        return null;
    }

    private static Optional<Source> source(MinecraftServer server, CombatOrigin origin) {
        return origin.directEntityId().map(direct -> sources(server).get(direct));
    }

    /** A human just took damage from {@code origin}: if it was left behind, its owner hears of it, at most every few seconds. */
    public static void damaged(ServerLevel level, ServerPlayer victim, CombatOrigin origin) {
        var server = level.getServer();
        var source = source(server, origin).orElse(null);
        if (source == null) return;
        var owner = server.getPlayerList().getPlayer(source.owner());
        if (owner == null) return;
        int now = server.getTickCount();
        String key = source.owner() + "|" + source.kind() + "|" + victim.getUUID();
        var hinted = HINTED.computeIfAbsent(server, ignored -> new HashMap<>());
        Integer last = hinted.get(key);
        if (last != null && now - last < HINT_INTERVAL_TICKS) return;
        hinted.put(key, now);
        if (hinted.size() > 512) hinted.values().removeIf(tick -> now - tick >= HINT_INTERVAL_TICKS);
        Notices.hint(owner, "你" + source.kind().told() + "伤到了 " + Notices.name(victim.getScoreboardName()));
    }

    /**
     * A human died to {@code credit}: if it was left behind, its owner is told in chat, since a revival totem arriving
     * out of nowhere needs explaining. {@code totem}: the monster earned the human's revival totem.
     */
    public static void killed(MinecraftServer server, CombatOrigin credit, ServerPlayer victim, boolean totem) {
        var source = source(server, credit).orElse(null);
        if (source == null) return;
        var owner = server.getPlayerList().getPlayer(source.owner());
        if (owner == null) return;
        Notices.personal(owner, "你" + source.kind().told() + "击败了 " + Notices.name(victim.getScoreboardName())
                + (totem ? "，复生图腾到手" : ""));
    }
}
