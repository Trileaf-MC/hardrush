package dev.hardrush.core.api;

import java.util.UUID;
import java.util.concurrent.CompletionStage;

/** Observer visuals are separate from authoritative identity and native-role combat. */
public interface DisguisePort {
    enum Appearance { HUMAN, ZOMBIE, SKELETON, CREEPER }

    /** HUMAN restores normal observers; the owner's camera and ordinary controls stay on the native path. */
    CompletionStage<OperationResult<Appearance>> apply(OperationTag tag, UUID playerId, Appearance appearance);
}
