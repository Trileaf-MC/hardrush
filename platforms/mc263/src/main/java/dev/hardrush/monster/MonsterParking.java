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
import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;

/**
 * The body a deliberate return parks in the round record: lightning charge, health, the witch's paid effects
 * with their remaining time, and the sortie's own bound items in their slots. Only what coins bought or the
 * sortie started with carries over: the body always resumes in its base form (no drowned, no stray), the
 * sea trident stays behind, and spent starting goods are never replenished. World items are never parked;
 * they settle where the monster left.
 */
public final class MonsterParking {
    /** Slot {@code -1} is the cursor; it rejoins the inventory on resume. */
    public record Body(MonsterRole role, MonsterForm form, boolean charged, float health, int selected,
                       Map<Integer, ItemStack> items, java.util.List<MobEffectInstance> effects) {}
    /** The witch's effects; their remaining time pauses in the camp. */
    private static final Map<String, Holder<MobEffect>> PAID_EFFECTS = Map.of("speed", MobEffects.SPEED,
            "fire_resistance", MobEffects.FIRE_RESISTANCE, "regeneration", MobEffects.REGENERATION);

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
            if (stack.isEmpty() || !MonsterItems.owned(player, stack)
                    || MonsterItems.purpose(stack).equals(MonsterRoles.SEA_TRIDENT)) continue;
            var entry = new JsonObject();
            entry.addProperty("slot", slot);
            entry.addProperty("item", MonsterGacha.encode(registries, stack));
            items.add(entry);
        }
        var effects = new JsonArray();
        PAID_EFFECTS.forEach((name, effect) -> {
            var active = player.getEffect(effect);
            if (active == null || active.isInfiniteDuration()) return;
            var entry = new JsonObject();
            entry.addProperty("effect", name);
            entry.addProperty("ticks", active.getDuration());
            entry.addProperty("amplifier", active.getAmplifier());
            effects.add(entry);
        });
        var body = new JsonObject();
        body.addProperty("form", base(form).name());
        body.addProperty("charged", form == MonsterForm.CREEPER && MonsterCreeper.charged(player));
        body.addProperty("health", player.getHealth());
        body.addProperty("selected", inventory.getSelectedSlot());
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
            var form = base(MonsterForm.valueOf(body.get("form").getAsString()));
            if (form.role != role) return Optional.empty();
            var effects = new java.util.ArrayList<MobEffectInstance>();
            if (body.has("effects")) for (var element : body.getAsJsonArray("effects")) {
                var entry = element.getAsJsonObject();
                var effect = PAID_EFFECTS.get(entry.get("effect").getAsString());
                if (effect != null) effects.add(new MobEffectInstance(effect, entry.get("ticks").getAsInt(), entry.get("amplifier").getAsInt()));
            }
            var items = new LinkedHashMap<Integer, ItemStack>();
            for (var element : body.getAsJsonArray("items")) {
                var entry = element.getAsJsonObject();
                var stack = MonsterGacha.decode(player.level().registryAccess(), entry.get("item").getAsString());
                if (!stack.isEmpty()) items.put(entry.get("slot").getAsInt(), stack);
            }
            return Optional.of(new Body(role, form, body.get("charged").getAsBoolean(), body.get("health").getAsFloat(),
                    body.get("selected").getAsInt(), items, java.util.List.copyOf(effects)));
        } catch (RuntimeException damaged) {
            return Optional.empty();
        }
    }

    /** What the camp calls the parked body, e.g. 带电苦力怕 or 溺尸. */
    public static Optional<String> describe(String text) {
        if (text.isEmpty()) return Optional.empty();
        try {
            var body = JsonParser.parseString(text).getAsJsonObject();
            var name = switch (base(MonsterForm.valueOf(body.get("form").getAsString()))) {
                case ZOMBIE -> "僵尸"; case DROWNED -> "溺尸"; case SKELETON -> "骷髅"; case STRAY -> "流浪者"; case CREEPER -> "苦力怕";
            };
            return Optional.of(body.get("charged").getAsBoolean() ? "带电" + name : name);
        } catch (RuntimeException damaged) { return Optional.empty(); }
    }

    /** Environmental forms are earned out there and stay there: a drowned parks as a zombie, a stray as a skeleton. */
    static MonsterForm base(MonsterForm form) {
        return switch (form.role) {
            case ZOMBIE -> MonsterForm.ZOMBIE; case SKELETON -> MonsterForm.SKELETON; case CREEPER -> MonsterForm.CREEPER;
        };
    }

    /** Health the camp body takes on after the return; the camp's native peaceful regeneration heals it from there. */
    public static Optional<Float> health(String text) {
        if (text.isEmpty()) return Optional.empty();
        try { return Optional.of(JsonParser.parseString(text).getAsJsonObject().get("health").getAsFloat()); }
        catch (RuntimeException damaged) { return Optional.empty(); }
    }
}
