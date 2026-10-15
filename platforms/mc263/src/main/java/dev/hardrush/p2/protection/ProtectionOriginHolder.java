package dev.hardrush.p2.protection;

/** Launch-time protection survives owner movement, disconnect and projectile NBT reload. */
public interface ProtectionOriginHolder {
    boolean hardrush$protectedOrigin();
    void hardrush$protectedOrigin(boolean protectedOrigin);
}
