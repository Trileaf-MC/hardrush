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
import net.minecraft.world.item.ItemStack;

/**
 * The body a deliberate return parks in the round record: form, lightning charge, health and the
 * sortie's own bound items in their slots. World items are never parked; they settle where the
 * monster left.
 */
public final class MonsterParking {
    /** Slot {@code -1} is the cursor; it rejoins the inventory on resume. */
    public record Body(MonsterRole role, MonsterForm form, boolean charged, float health, int selected,
                       Map<Integer, ItemStack> items) {}

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
        var body = new JsonObject();
        body.addProperty("form", form.name());
        body.addProperty("charged", form == MonsterForm.CREEPER && MonsterCreeper.charged(player));
        body.addProperty("health", player.getHealth());
        body.addProperty("selected", inventory.getSelectedSlot());
        body.add("items", items);
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
            var items = new LinkedHashMap<Integer, ItemStack>();
            for (var element : body.getAsJsonArray("items")) {
                var entry = element.getAsJsonObject();
                var stack = MonsterGacha.decode(player.level().registryAccess(), entry.get("item").getAsString());
                if (!stack.isEmpty()) items.put(entry.get("slot").getAsInt(), stack);
            }
            return Optional.of(new Body(role, form, body.get("charged").getAsBoolean(), body.get("health").getAsFloat(),
                    body.get("selected").getAsInt(), items));
        } catch (RuntimeException damaged) {
            return Optional.empty();
        }
    }

    /** What the camp calls the parked body, e.g. 带电苦力怕 or 溺尸. */
    public static Optional<String> describe(String text) {
        if (text.isEmpty()) return Optional.empty();
        try {
            var body = JsonParser.parseString(text).getAsJsonObject();
            var name = switch (MonsterForm.valueOf(body.get("form").getAsString())) {
                case ZOMBIE -> "僵尸"; case DROWNED -> "溺尸"; case SKELETON -> "骷髅"; case STRAY -> "流浪者"; case CREEPER -> "苦力怕";
            };
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
