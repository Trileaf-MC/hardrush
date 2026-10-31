package dev.hardrush.pact;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.hardrush.core.combat.DuskPact;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.storage.SavedDataStorage;

/** Saved beside this round's independent clock; a time rewind must never grant another grace period. */
public final class DuskPactData extends SavedData {
    public static final Codec<DuskPactData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.BOOL.fieldOf("started").forGetter(data -> data.started),
            Codec.BOOL.fieldOf("released").forGetter(data -> data.released)
    ).apply(instance, DuskPactData::new));
    private static final SavedDataType<DuskPactData> TYPE = new SavedDataType<>(Identifier.parse("hardrush:dusk_pact"),
            () -> new DuskPactData(false, false), CODEC, null);
    private boolean started;
    private boolean released;

    public DuskPactData(boolean started, boolean released) { this.started = started; this.released = released; }

    public static DuskPactData get(ServerLevel overworld, boolean existingChallenge) {
        return get(overworld.getDataStorage(), existingChallenge);
    }
    public static DuskPactData get(SavedDataStorage storage, boolean existingChallenge) {
        var data = storage.get(TYPE);
        if (data == null) {
            data = new DuskPactData(existingChallenge, false);
            storage.set(TYPE, data);
        }
        return data;
    }
    public boolean started() { return started; }
    public boolean released() { return released; }
    public void start() { if (!started) { started = true; setDirty(); } }
    public boolean protectedAt(long ticks) {
        if (started && !released && ticks >= DuskPact.DUSK_TICKS) { released = true; setDirty(); }
        return started && !released;
    }
}
