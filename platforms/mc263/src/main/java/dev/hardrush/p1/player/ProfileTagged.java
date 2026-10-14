package dev.hardrush.p1.player;

import java.util.Optional;
import net.minecraft.nbt.CompoundTag;

/** Generation and quarantine flags persist in vanilla player NBT, without replacing cumulative statistics. */
public interface ProfileTagged {
    Optional<ProfileJournal.Generation> hardrush$generation();
    void hardrush$generation(ProfileJournal.Generation generation);
    boolean hardrush$isolated();
    void hardrush$isolated(boolean isolated);
    String hardrush$suspensionId();
    void hardrush$suspensionId(String suspensionId);
    Optional<CompoundTag> hardrush$loadedProfile();
    void hardrush$clearLoadedProfile();
    int hardrush$lifeSequence();
    void hardrush$lifeSequence(int lifeSequence);
    boolean hardrush$respawnPermitted();
    void hardrush$respawnPermitted(boolean permitted);
}
