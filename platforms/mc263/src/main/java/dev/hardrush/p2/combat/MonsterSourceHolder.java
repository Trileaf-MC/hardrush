package dev.hardrush.p2.combat;

import java.util.Optional;

/** The first formal monster identity is permanent; changing a projectile owner cannot replace it. */
public interface MonsterSourceHolder {
    Optional<FrozenMonsterSource> hardrush$monsterSource();
    void hardrush$stampMonsterSource(FrozenMonsterSource source);
}
