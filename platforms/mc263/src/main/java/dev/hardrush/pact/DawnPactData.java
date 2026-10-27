package dev.hardrush.pact;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.hardrush.core.combat.DawnPact;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.storage.SavedDataStorage;

/** Saved beside this round's independent clock; a time rewind must never grant another grace period. */
public final class DawnPactData extends SavedData {
    public static final Codec<DawnPactData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.BOOL.fieldOf("started").forGetter(data -> data.started),
            Codec.BOOL.fieldOf("released").forGetter(data -> data.released)
    ).apply(instance, DawnPactData::new));
    private static final SavedDataType<DawnPactData> TYPE = new SavedDataType<>(Identifier.parse("hardrush:dawn_pact"),
            () -> new DawnPactData(false, false), CODEC, null);
    private boolean started;
    private boolean released;

    public DawnPactData(boolean started, boolean released) { this.started = started; this.released = released; }

    public static DawnPactData get(ServerLevel overworld, boolean existingChallenge) {
        return get(overworld.getDataStorage(), existingChallenge);
    }
    public static DawnPactData get(SavedDataStorage storage, boolean existingChallenge) {
        var data = storage.get(TYPE);
        if (data == null) {
            data = new DawnPactData(existingChallenge, false);
            storage.set(TYPE, data);
        }
        return data;
    }
    public boolean started() { return started; }
    public boolean released() { return released; }
    public void start() { if (!started) { started = true; setDirty(); } }
    public boolean protectedAt(long ticks) {
        if (started && !released && ticks >= DawnPact.SUNRISE_TICKS) { released = true; setDirty(); }
        return started && !released;
    }
}
