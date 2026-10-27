package dev.hardrush.pact;

import net.minecraft.world.entity.EntityReference;

/** Vanilla persists this reference even while the shooter/lingering-potion owner is offline. */
public interface PactOwnerReference {
    EntityReference<?> hardrush$pactOwner();
}
