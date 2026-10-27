package dev.hardrush.player.mixin;

import dev.hardrush.player.ProfileJournal;
import dev.hardrush.player.ProfileTagged;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayer.class)
public abstract class PlayerProfileTagMixin implements ProfileTagged {
    @Unique private ProfileJournal.Generation hardrush$generation;
    @Unique private boolean hardrush$isolated;
    @Unique private String hardrush$suspensionId = "";
    @Unique private CompoundTag hardrush$loadedProfile;
    @Unique private int hardrush$lifeSequence;
    @Unique private boolean hardrush$respawnPermitted;

    @Override public Optional<ProfileJournal.Generation> hardrush$generation() { return Optional.ofNullable(hardrush$generation); }
    @Override public void hardrush$generation(ProfileJournal.Generation generation) { hardrush$generation = generation; }
    @Override public boolean hardrush$isolated() { return hardrush$isolated; }
    @Override public void hardrush$isolated(boolean isolated) { hardrush$isolated = isolated; }
    @Override public String hardrush$suspensionId() { return hardrush$suspensionId; }
    @Override public void hardrush$suspensionId(String suspensionId) { hardrush$suspensionId = suspensionId; }
    @Override public Optional<CompoundTag> hardrush$loadedProfile() { return Optional.ofNullable(hardrush$loadedProfile); }
    @Override public void hardrush$clearLoadedProfile() { hardrush$loadedProfile = null; }
    @Override public int hardrush$lifeSequence() { return hardrush$lifeSequence; }
    @Override public void hardrush$lifeSequence(int lifeSequence) { hardrush$lifeSequence = lifeSequence; }
    @Override public boolean hardrush$respawnPermitted() { return hardrush$respawnPermitted; }
    @Override public void hardrush$respawnPermitted(boolean permitted) { hardrush$respawnPermitted = permitted; }

    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void hardrush$loadGeneration(ValueInput input, CallbackInfo ci) {
        hardrush$generation = null;
        hardrush$isolated = input.getBooleanOr("hardrush_isolated", false);
        hardrush$suspensionId = input.getStringOr("hardrush_suspension_id", "");
        hardrush$lifeSequence = Math.max(0, input.getIntOr("hardrush_life_sequence", 0));
        hardrush$respawnPermitted = false;
        hardrush$loadedProfile = Boolean.getBoolean("hardrush.p1.enabled") && input instanceof PlayerProfileInputAccessor accessor
                ? accessor.hardrush$input().copy() : null;
        var id = input.getString("hardrush_round_id");
        long sequence = input.getLongOr("hardrush_generation", 0);
        if (id.isPresent()) {
            try { hardrush$generation = new ProfileJournal.Generation(UUID.fromString(id.get()), sequence); }
            catch (IllegalArgumentException invalid) { hardrush$isolated = true; }
        }
    }

    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void hardrush$saveGeneration(ValueOutput output, CallbackInfo ci) {
        if (hardrush$generation != null) {
            output.putString("hardrush_round_id", hardrush$generation.roundId().toString());
            output.putLong("hardrush_generation", hardrush$generation.sequence());
        }
        output.putBoolean("hardrush_isolated", hardrush$isolated);
        output.putString("hardrush_suspension_id", hardrush$suspensionId);
        output.putInt("hardrush_life_sequence", hardrush$lifeSequence);
    }
}
