package dev.hardrush.core.admin;

/** Identity must come from the authenticated command source (UUID or dedicated console identity). */
public record AdminActor(String identity, String displayName) {
    public AdminActor {
        identity = AdminText.text(identity, 256);
        displayName = AdminText.text(displayName, 128);
    }
}
