package dev.hardrush.core.admin;

import java.util.Objects;
import java.util.UUID;

/**
 * The exact operation shown to its operator. contextTag binds semantic active-round state or a
 * historical edition; it must change whenever the operation's displayed impact changes. Ordinary
 * timing checkpoints need not change it. The execution layer still commits against its current CAS.
 */
public record AdminRequest(AdminActor actor, AdminAction action, UUID roundId,
                           String contextTag, String reason, String impact) {
    public AdminRequest {
        Objects.requireNonNull(actor); Objects.requireNonNull(action); Objects.requireNonNull(roundId);
        contextTag = AdminText.text(contextTag, 4096);
        reason = AdminText.reason(reason);
        impact = AdminText.text(impact, 1024);
    }
}
