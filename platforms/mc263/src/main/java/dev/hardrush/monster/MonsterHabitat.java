package dev.hardrush.monster;

import dev.hardrush.world.RoundWorldRouting;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;

/**
 * Which body a monster lands in (docs/plan/怪物环境变种.md §1): the variant vanilla would have spawned at the
 * landing site instead. Judged only when a body is put down somewhere new, never along the way.
 */
public final class MonsterHabitat {
    private MonsterHabitat() {}

    /**
     * What a landing site is like.
     * @param submerged a landing under water ({@code SafeDeployment.Result.submerged()})
     * @param nether the round's nether, whatever the level is called
     * @param snowing snow, not rain, falls at the feet
     * @param openSky the feet see the sky
     * @param natives the monsters the biome's own spawn table lists there
     */
    public record Site(boolean submerged, boolean nether, boolean snowing, boolean openSky, Set<EntityType<?>> natives) {
        public Site { natives = Set.copyOf(natives); }
        /** Whether the biome spawns {@code type} as a monster. */
        public boolean hosts(EntityType<?> type) { return natives.contains(type); }
    }

    /** The site at {@code feet} in {@code level}; {@code submerged} comes from the deployment search. */
    public static Site at(ServerLevel level, BlockPos feet, boolean submerged) {
        boolean nether = Level.NETHER.equals(RoundWorldRouting.semanticDimension(level));
        boolean snowing = level.getBiome(feet).value().getPrecipitationAt(feet, level.getSeaLevel()) == Biome.Precipitation.SNOW;
        var natives = new HashSet<EntityType<?>>();
        for (var spawner : level.environmentAttributes().getValue(EnvironmentAttributes.NATURAL_MOB_SPAWNS, feet)
                .getMobsToSpawn(MobCategory.MONSTER).unwrap()) natives.add(spawner.value().type());
        return new Site(submerged, nether, snowing, level.canSeeSky(feet), natives);
    }

    /** The form a body that is {@code current} lands as at {@code site} on a sortie: {@link #settle(MonsterForm, Site, boolean)}. */
    public static MonsterForm settle(MonsterForm current, Site site) { return settle(current, site, false); }

    /**
     * The form a body that is {@code current} lands as at {@code site}. A base form takes the first variant of its
     * role that suits the site, in {@link MonsterForm} declaration order; a variant keeps itself for the rest of
     * the sortie unless a landing that {@linkplain MonsterForm.Landing#overridesVariant overrides} it suits.
     * @param retrack a retrack's landing, which takes only the landings {@linkplain MonsterForm.Landing#onRetrack
     *        made for it}
     */
    public static MonsterForm settle(MonsterForm current, Site site, boolean retrack) {
        for (var form : MonsterForm.values()) {
            var landing = form.landing();
            if (form == current || form.role != current.role || landing == null || retrack && !landing.onRetrack()) continue;
            if ((current.base || landing.overridesVariant()) && landing.where().test(site)) return form;
        }
        return current;
    }
}
