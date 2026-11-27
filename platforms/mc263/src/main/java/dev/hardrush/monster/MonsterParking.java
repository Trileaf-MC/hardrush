package dev.hardrush.monster;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.hardrush.core.round.MonsterRole;
import dev.hardrush.core.round.MonsterWallet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.ItemStack;

/**
 * The body a deliberate return parks in the round record: lightning charge, health, every effect it drank or was hit
 * with, with its remaining time (a parting gift's infinite one included), the form the environment turned it into (drowned, stray) and the sortie's own bound
 * items in their slots, a drowned's trident among them. Spent starting goods are never replenished. World items
 * are never parked; they settle where the monster left.
 */
public final class MonsterParking {
    /**
     * Slot {@code -1} is the cursor; it rejoins the inventory on resume. {@code tier} is the tier the sortie's own
     * goods were issued for, 0 for a body parked before tiers; resuming onto a weaker human takes back what exceeds it.
     * {@code absorption} is what was left of the yellow hearts: a resumed absorption effect must not refill them.
     */
    public record Body(MonsterRole role, MonsterForm form, boolean charged, float health, float absorption, int selected,
                       Map<Integer, ItemStack> items, java.util.List<MobEffectInstance> effects, int tier) {}

    private MonsterParking() {}

    /** The encoded body, or empty when it cannot fit the record and the sortie must end instead. */
    public static String capture(ServerPlayer player) {
        var form = MonsterRoles.currentForm(player);
        if (form == null) return "";
        var registries = player.level().registryAccess();
        var items = new JsonArray();
        var inventory = player.getInventory();
        for (int slot = -1; slot < inventory.getContainerSize(); slot++) {
            var stack = slot < 0 ? player.containerMenu.getCarried() : inventory.getItem(slot);
            if (stack.isEmpty() || !MonsterItems.owned(player, stack)) continue;
            var entry = new JsonObject();
            entry.addProperty("slot", slot);
            entry.addProperty("item", MonsterGacha.encode(registries, stack));
            items.add(entry);
        }
        var effects = new JsonArray();
        for (var active : player.getActiveEffects()) {
            // Only the body's own effects (night vision) stay behind; the next bind
            // gives them afresh. A parting gift lasts until death and parks like the rest.
            if (MonsterRoles.intrinsic(active)) continue;
            MobEffectInstance.CODEC.encodeStart(registries.createSerializationContext(com.mojang.serialization.JsonOps.INSTANCE), active)
                    .result().ifPresent(encoded -> {
                        var entry = new JsonObject();
                        entry.add("instance", encoded);
                        effects.add(entry);
                    });
        }
        var body = new JsonObject();
        body.addProperty("form", form.name());
        body.addProperty("charged", form == MonsterForm.CREEPER && MonsterCreeper.charged(player));
        body.addProperty("health", player.getHealth());
        body.addProperty("absorption", player.getAbsorptionAmount());
        body.addProperty("selected", inventory.getSelectedSlot());
        body.addProperty("tier", MonsterRoundRuntime.sortieTier(player));
        body.add("items", items);
        body.add("effects", effects);
        var text = body.toString();
        return text.length() > MonsterWallet.MAX_PARKED_TEXT ? "" : text;
    }

    /** The parked body if it still decodes and belongs to {@code role}; a damaged record starts the sortie afresh. */
    public static Optional<Body> decode(ServerPlayer player, String text, MonsterRole role) {
        if (text.isEmpty()) return Optional.empty();
        try {
            var body = JsonParser.parseString(text).getAsJsonObject();
            var form = MonsterForm.valueOf(body.get("form").getAsString());
            if (form.role != role) return Optional.empty();
            var effects = new java.util.ArrayList<MobEffectInstance>();
            if (body.has("effects")) for (var element : body.getAsJsonArray("effects")) {
                var entry = element.getAsJsonObject();
                if (!entry.has("instance")) continue;
                MobEffectInstance.CODEC.parse(player.level().registryAccess().createSerializationContext(
                        com.mojang.serialization.JsonOps.INSTANCE), entry.get("instance")).result().ifPresent(effects::add);
            }
            var items = new LinkedHashMap<Integer, ItemStack>();
            for (var element : body.getAsJsonArray("items")) {
                var entry = element.getAsJsonObject();
                var stack = MonsterGacha.decode(player.level().registryAccess(), entry.get("item").getAsString());
                if (!stack.isEmpty()) items.put(entry.get("slot").getAsInt(), stack);
            }
            return Optional.of(new Body(role, form, body.get("charged").getAsBoolean(), body.get("health").getAsFloat(),
                    body.has("absorption") ? body.get("absorption").getAsFloat() : 0, body.get("selected").getAsInt(), items, java.util.List.copyOf(effects), body.has("tier") ? body.get("tier").getAsInt() : 0));
        } catch (RuntimeException damaged) {
            return Optional.empty();
        }
    }

    /** What the camp calls the parked body, e.g. 带电苦力怕 or 溺尸. */
    public static Optional<String> describe(String text) {
        if (text.isEmpty()) return Optional.empty();
        try {
            var body = JsonParser.parseString(text).getAsJsonObject();
            var name = MonsterForm.valueOf(body.get("form").getAsString()).label;
            return Optional.of(body.get("charged").getAsBoolean() ? "带电" + name : name);
        } catch (RuntimeException damaged) { return Optional.empty(); }
    }

    /** Health the camp body takes on after the return; the camp's native peaceful regeneration heals it from there. */
    public static Optional<Float> health(String text) {
        if (text.isEmpty()) return Optional.empty();
        try { return Optional.of(JsonParser.parseString(text).getAsJsonObject().get("health").getAsFloat()); }
        catch (RuntimeException damaged) { return Optional.empty(); }
    }
}
