package dev.hardrush.monster;

import static org.junit.jupiter.api.Assertions.*;

import dev.hardrush.monster.MonsterHabitat.Site;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Each form carries everything that differs between species, and the landing table of docs/plan/怪物环境变种.md §1. */
class MonsterFormTest {
    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        DESERT = Set.of(EntityTypes.ZOMBIE, EntityTypes.HUSK, EntityTypes.SKELETON, EntityTypes.PARCHED);
        SWAMP = Set.of(EntityTypes.ZOMBIE, EntityTypes.SKELETON, EntityTypes.BOGGED);
        PLAINS = Set.of(EntityTypes.ZOMBIE, EntityTypes.SKELETON, EntityTypes.CREEPER);
    }

    // Assigned after the bootstrap: entity types need the registries.
    private static Set<EntityType<?>> DESERT, SWAMP, PLAINS;

    private static Site land(Set<EntityType<?>> natives, boolean openSky) { return new Site(false, false, false, openSky, natives); }

    @Test void everyFormCarriesWhatItsRoleNeeds() {
        for (var form : MonsterForm.values()) {
            assertNotNull(form.label, form.name());
            assertNotNull(form.hurtSound, form.name());
            assertNotNull(form.deathSound, form.name());
            assertEquals(form.zombie(), form.egg != null, "Zombie-line forms, and only they, hatch eggs: " + form);
            assertEquals(form.skeleton(), form.bowName != null, "Skeleton-line forms, and only they, name a bow: " + form);
            var base = form.baseForm();
            assertTrue(base.base && base.role == form.role, "Every form has a base of its own role: " + form);
            assertEquals(form, MonsterForm.byId(form.id()));
            if (form.conversion() != null) assertEquals(form.role, form.conversion().into().role, "Conversion keeps the role: " + form);
        }
        for (var role : dev.hardrush.core.round.MonsterRole.values())
            assertEquals(1, java.util.Arrays.stream(MonsterForm.values()).filter(form -> form.role == role && form.base).count(),
                    "One selectable form per role: " + role);
    }

    @Test void theNewVariantsAreDeclared() {
        assertEquals(MonsterForm.ZOMBIE, MonsterForm.HUSK.baseForm());
        assertEquals(MonsterForm.ZOMBIE, MonsterForm.ZOMBIFIED_PIGLIN.baseForm());
        assertEquals(MonsterForm.SKELETON, MonsterForm.BOGGED.baseForm());
        assertEquals(MonsterForm.SKELETON, MonsterForm.PARCHED.baseForm());
        assertEquals(.8, MonsterForm.BOGGED.maxHealthScale);
        assertEquals(.8, MonsterForm.PARCHED.maxHealthScale);
        assertEquals(MonsterForm.ZOMBIE, MonsterForm.HUSK.conversion().into());
        assertNull(MonsterForm.ZOMBIFIED_PIGLIN.conversion());
        assertEquals(200, MonsterForm.STRAY.arrowEffect().maxTicks());
        assertEquals(200, MonsterForm.PARCHED.arrowEffect().maxTicks());
        assertEquals(100, MonsterForm.BOGGED.arrowEffect().maxTicks());
        assertNull(MonsterForm.SKELETON.arrowEffect());
    }

    @Test void waterMakesEveryZombieLineBodyADrowned() {
        var sea = new Site(true, false, false, false, Set.of());
        for (var form : MonsterForm.values()) if (form.zombie())
            assertEquals(MonsterForm.DROWNED, MonsterHabitat.settle(form, sea), form.name());
        var netherSea = new Site(true, true, false, true, DESERT);
        assertEquals(MonsterForm.DROWNED, MonsterHabitat.settle(MonsterForm.ZOMBIE, netherSea), "Water outranks the nether");
        assertEquals(MonsterForm.SKELETON, MonsterHabitat.settle(MonsterForm.SKELETON, sea), "A skeleton under water stays one");
    }

    @Test void theNetherGivesZombifiedPiglins() {
        var nether = new Site(false, true, false, false, Set.of(EntityTypes.ZOMBIFIED_PIGLIN));
        assertEquals(MonsterForm.ZOMBIFIED_PIGLIN, MonsterHabitat.settle(MonsterForm.ZOMBIE, nether));
        assertEquals(MonsterForm.SKELETON, MonsterHabitat.settle(MonsterForm.SKELETON, nether));
        assertEquals(MonsterForm.HUSK, MonsterHabitat.settle(MonsterForm.HUSK, nether), "A variant keeps itself");
    }

    @Test void theOpenDesertGivesHusksAndParched() {
        assertEquals(MonsterForm.HUSK, MonsterHabitat.settle(MonsterForm.ZOMBIE, land(DESERT, true)));
        assertEquals(MonsterForm.PARCHED, MonsterHabitat.settle(MonsterForm.SKELETON, land(DESERT, true)));
        assertEquals(MonsterForm.ZOMBIE, MonsterHabitat.settle(MonsterForm.ZOMBIE, land(DESERT, false)), "Under cover a zombie stays one");
        assertEquals(MonsterForm.SKELETON, MonsterHabitat.settle(MonsterForm.SKELETON, land(DESERT, false)), "Under cover a skeleton stays one");
    }

    @Test void snowGivesStraysUnderTheSkyOnly() {
        var snow = new Site(false, false, true, true, PLAINS);
        assertEquals(MonsterForm.STRAY, MonsterHabitat.settle(MonsterForm.SKELETON, snow));
        assertEquals(MonsterForm.SKELETON, MonsterHabitat.settle(MonsterForm.SKELETON, new Site(false, false, true, false, PLAINS)));
        assertEquals(MonsterForm.ZOMBIE, MonsterHabitat.settle(MonsterForm.ZOMBIE, snow), "Snow turns no zombie");
        assertEquals(MonsterForm.STRAY, MonsterHabitat.settle(MonsterForm.SKELETON, new Site(false, false, true, true, DESERT)),
                "Snow comes before the desert's parched");
    }

    @Test void swampsGiveBoggedEvenUnderCover() {
        assertEquals(MonsterForm.BOGGED, MonsterHabitat.settle(MonsterForm.SKELETON, land(SWAMP, true)));
        assertEquals(MonsterForm.BOGGED, MonsterHabitat.settle(MonsterForm.SKELETON, land(SWAMP, false)));
        assertEquals(MonsterForm.ZOMBIE, MonsterHabitat.settle(MonsterForm.ZOMBIE, land(SWAMP, true)));
    }

    @Test void aVariantKeepsItselfAndPlainLandsChangeNothing() {
        assertEquals(MonsterForm.STRAY, MonsterHabitat.settle(MonsterForm.STRAY, land(DESERT, true)));
        assertEquals(MonsterForm.BOGGED, MonsterHabitat.settle(MonsterForm.BOGGED, new Site(false, false, true, true, DESERT)));
        assertEquals(MonsterForm.ZOMBIFIED_PIGLIN, MonsterHabitat.settle(MonsterForm.ZOMBIFIED_PIGLIN, land(DESERT, true)));
        assertEquals(MonsterForm.DROWNED, MonsterHabitat.settle(MonsterForm.DROWNED, land(DESERT, true)), "A drowned ashore stays one");
        assertEquals(MonsterForm.ZOMBIE, MonsterHabitat.settle(MonsterForm.ZOMBIE, land(PLAINS, true)));
        assertEquals(MonsterForm.SKELETON, MonsterHabitat.settle(MonsterForm.SKELETON, land(PLAINS, true)));
    }

    @Test void aCreeperNeverTurns() {
        for (var site : java.util.List.of(new Site(true, true, true, true, DESERT), land(SWAMP, true), land(DESERT, true)))
            assertEquals(MonsterForm.CREEPER, MonsterHabitat.settle(MonsterForm.CREEPER, site));
    }
}
