package dev.hardrush.core.round;

import dev.hardrush.core.config.RulesConfig;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * A monster's coins and purchases for this round. Purchased goods are durable orders, never
 * items: the camp only projects them, and a sortie receives exactly what deployment froze.
 *
 * @param bought       purchases since the last sortie ended; drives the doubling price ladder
 * @param order        paid goods waiting for the next sortie, including unopened vault keys
 * @param active       consumables frozen by the current deployment
 * @param gear         vault results waiting for a role that can wear them
 * @param activeGear   vault results carried by the current deployment
 * @param sortieDamage per-human damage credit of the current or most recently ended deployment
 */
public record MonsterWallet(int coins, boolean roleChosen,
                            Map<ShopOffer, Integer> bought, Map<ShopOffer, Integer> order,
                            Map<ShopOffer, Integer> active, List<GearPiece> gear, List<GearPiece> activeGear,
                            int nextGearSerial, Map<UUID, DamageCredit> sortieDamage) {
    public static final int MAX_GEAR = 16;
    public static final int MAX_GEAR_TEXT = 8192;
    public static final int MAX_DAMAGE_VICTIMS = 256;
    public static final MonsterWallet EMPTY = new MonsterWallet(0, false, Map.of(), Map.of(), Map.of(),
            List.of(), List.of(), 0, Map.of());

    public enum GearSlot { HEAD, CHEST, LEGS, FEET, MAINHAND }

    /** {@code item} is the platform's encoded native stack; core never interprets it. */
    public record GearPiece(int serial, GearSlot slot, Set<MonsterRole> roles, String item) {
        public GearPiece {
            Objects.requireNonNull(slot); Objects.requireNonNull(item);
            roles = Set.copyOf(roles);
            if (serial < 0 || roles.isEmpty() || item.isEmpty() || item.length() > MAX_GEAR_TEXT) {
                throw new IllegalArgumentException("Invalid vault gear");
            }
        }
        public boolean wearableBy(MonsterRole role) { return roles.contains(role); }
    }

    /** Milli-health counted toward the per-human cap, its bounty-weighted value, and whole coins paid. */
    public record DamageCredit(long countedMilli, long weightedMilli, int paid) {
        public DamageCredit {
            if (countedMilli < 0 || weightedMilli < 0 || paid < 0) throw new IllegalArgumentException("Invalid damage credit");
        }
    }

    public MonsterWallet {
        bought = counts(bought); order = counts(order); active = counts(active);
        gear = List.copyOf(gear); activeGear = List.copyOf(activeGear);
        sortieDamage = Map.copyOf(sortieDamage);
        if (coins < 0 || nextGearSerial < 0 || gear.size() + activeGear.size() > MAX_GEAR
                || sortieDamage.size() > MAX_DAMAGE_VICTIMS) {
            throw new IllegalArgumentException("Invalid monster wallet");
        }
        if (active.keySet().stream().anyMatch(ShopOffer::key)) {
            throw new IllegalArgumentException("Vault keys are never carried into a sortie");
        }
    }

    private static Map<ShopOffer, Integer> counts(Map<ShopOffer, Integer> source) {
        var copy = new EnumMap<ShopOffer, Integer>(ShopOffer.class);
        source.forEach((offer, count) -> {
            Objects.requireNonNull(offer);
            if (count == null || count < 0) throw new IllegalArgumentException("Invalid offer count");
            if (count > 0) copy.put(offer, count);
        });
        return Map.copyOf(copy);
    }

    public int bought(ShopOffer offer) { return bought.getOrDefault(offer, 0); }
    public int ordered(ShopOffer offer) { return order.getOrDefault(offer, 0); }
    public int carried(ShopOffer offer) { return active.getOrDefault(offer, 0); }

    private MonsterWallet with(int newCoins, boolean chosen, Map<ShopOffer, Integer> newBought,
                               Map<ShopOffer, Integer> newOrder, Map<ShopOffer, Integer> newActive,
                               List<GearPiece> newGear, List<GearPiece> newActiveGear, int serial,
                               Map<UUID, DamageCredit> damage) {
        return new MonsterWallet(newCoins, chosen, newBought, newOrder, newActive, newGear, newActiveGear,
                serial, damage);
    }

    public MonsterWallet credit(int amount) {
        if (amount < 0) throw new IllegalArgumentException("Negative coin credit");
        if (amount == 0) return this;
        return with((int) Math.min(Integer.MAX_VALUE, (long) coins + amount), roleChosen, bought,
                order, active, gear, activeGear, nextGearSerial, sortieDamage);
    }

    /** Every new monster life starts with the gift; revival already emptied the previous wallet. */
    public MonsterWallet becameMonster(int starter) {
        var wallet = credit(starter);
        return wallet.with(wallet.coins, false, bought, order, active, gear, activeGear, nextGearSerial, sortieDamage);
    }

    public int nextPrice(RulesConfig rules, ShopOffer offer) {
        return MonsterCoins.price(rules, offer, bought(offer));
    }

    public boolean soldOut(RulesConfig rules, ShopOffer offer) {
        return bought(offer) >= MonsterCoins.limit(rules, offer);
    }

    public MonsterWallet buy(RulesConfig rules, MonsterRole role, ShopOffer offer, int expectedPrice) {
        Objects.requireNonNull(role); Objects.requireNonNull(offer);
        if (!roleChosen) throw new IllegalStateException("请先在展位选择本次要化身的怪物。");
        if (!offer.sellableTo(role)) throw new IllegalStateException("女巫不向当前角色出售" + offer.title() + "。");
        if (soldOut(rules, offer)) throw new IllegalStateException(offer.title() + "本次出场已售罄。");
        int price = nextPrice(rules, offer);
        if (price != expectedPrice) throw new IllegalStateException("价格已变化，请重新选择交易。");
        if (coins < price) throw new IllegalStateException("怪物币不足：需要 " + price + "，现有 " + coins + "。");
        return with(coins - price, roleChosen, add(bought, offer, 1), add(order, offer, 1), active,
                gear, activeGear, nextGearSerial, sortieDamage);
    }

    /** A committed role choice; goods the new role cannot use are refunded at the price paid. */
    public MonsterWallet chooseRole(RulesConfig rules, MonsterRole role) {
        var wallet = refund(rules, offer -> !offer.sellableTo(role));
        return wallet.with(wallet.coins, true, wallet.bought, wallet.order, active, gear, activeGear,
                nextGearSerial, sortieDamage);
    }

    private MonsterWallet refund(RulesConfig rules, java.util.function.Predicate<ShopOffer> filter) {
        long total = coins;
        var newBought = new EnumMap<ShopOffer, Integer>(ShopOffer.class); newBought.putAll(bought);
        var newOrder = new EnumMap<ShopOffer, Integer>(ShopOffer.class); newOrder.putAll(order);
        for (var entry : order.entrySet()) {
            if (!filter.test(entry.getKey())) continue;
            int had = bought(entry.getKey());
            int count = Math.min(entry.getValue(), had);
            total += MonsterCoins.refund(rules, entry.getKey(), had, count);
            newBought.put(entry.getKey(), had - count);
            newOrder.remove(entry.getKey());
        }
        return with((int) Math.min(Integer.MAX_VALUE, total), roleChosen, newBought, newOrder, active,
                gear, activeGear, nextGearSerial, sortieDamage);
    }

    public boolean gearFull() { return gear.size() + activeGear.size() >= MAX_GEAR; }

    public MonsterWallet openVault(ShopOffer key, GearSlot slot, Set<MonsterRole> roles, String item) {
        if (!key.key()) throw new IllegalArgumentException("Not a vault key");
        if (ordered(key) <= 0) throw new IllegalStateException("没有可用的" + key.title() + "。");
        if (gearFull()) throw new IllegalStateException("营地装备已满 " + MAX_GEAR + " 件，请先出场使用。");
        var newGear = new ArrayList<>(gear);
        newGear.add(new GearPiece(nextGearSerial, slot, roles, item));
        return with(coins, roleChosen, bought, add(order, key, -1), active, newGear, activeGear,
                Math.addExact(nextGearSerial, 1), sortieDamage);
    }

    /** Unopened keys are refunded; the role's goods and wearable gear are frozen for this sortie. */
    public MonsterWallet deploy(RulesConfig rules, MonsterRole role) {
        var wallet = refund(rules, ShopOffer::key);
        var carriedGear = wallet.gear.stream().filter(piece -> piece.wearableBy(role)).toList();
        var keptGear = wallet.gear.stream().filter(piece -> !piece.wearableBy(role)).toList();
        var carried = new EnumMap<ShopOffer, Integer>(ShopOffer.class);
        var kept = new EnumMap<ShopOffer, Integer>(ShopOffer.class);
        wallet.order.forEach((offer, count) -> (offer.sellableTo(role) ? carried : kept).put(offer, count));
        return with(wallet.coins, roleChosen, wallet.bought, kept, carried, keptGear, carriedGear,
                nextGearSerial, Map.of());
    }

    /** Compensation for an outing that never opened: everything frozen returns to the camp. */
    public MonsterWallet cancelDeploy() {
        var restored = new EnumMap<ShopOffer, Integer>(ShopOffer.class); restored.putAll(order);
        active.forEach((offer, count) -> restored.merge(offer, count, Integer::sum));
        var restoredGear = new ArrayList<>(gear); restoredGear.addAll(activeGear);
        restoredGear.sort(Comparator.comparingInt(GearPiece::serial));
        return with(coins, roleChosen, bought, restored, Map.of(), restoredGear, List.of(),
                nextGearSerial, Map.of());
    }

    /**
     * Return, death or disconnect: the carried goods are spent and the price ladder restarts. The
     * sortie's damage credit stays until the next deployment, so late buffered damage keeps its cap.
     */
    public MonsterWallet endSortie() {
        // Goods still waiting in the camp keep their paid ladder steps for an exact refund.
        var waiting = new EnumMap<ShopOffer, Integer>(ShopOffer.class);
        bought.forEach((offer, count) -> { if (order.containsKey(offer)) waiting.put(offer, count); });
        return with(coins, roleChosen, waiting, order, Map.of(), gear, List.of(), nextGearSerial, sortieDamage);
    }

    /**
     * Counts at most {@code capMilli} of actual damage per human and sortie; the bounty multiplies
     * what was counted. Whole coins are paid as the weighted total crosses each integer.
     */
    public MonsterWallet creditDamage(UUID victim, long damageMilli, int bountyPermille, long capMilli) {
        Objects.requireNonNull(victim);
        if (damageMilli <= 0 || bountyPermille <= 0 || capMilli <= 0) return this;
        var credit = sortieDamage.getOrDefault(victim, new DamageCredit(0, 0, 0));
        if (!sortieDamage.containsKey(victim) && sortieDamage.size() >= MAX_DAMAGE_VICTIMS) return this;
        long counted = Math.min(damageMilli, Math.max(0, capMilli - credit.countedMilli()));
        if (counted == 0) return this;
        long weighted = Math.addExact(credit.weightedMilli(), Math.multiplyExact(counted, bountyPermille) / 1000);
        int paid = (int) Math.min(Integer.MAX_VALUE, weighted / 1000);
        var damage = new LinkedHashMap<>(sortieDamage);
        damage.put(victim, new DamageCredit(credit.countedMilli() + counted, weighted, paid));
        return with((int) Math.min(Integer.MAX_VALUE, (long) coins + paid - credit.paid()), roleChosen,
                bought, order, active, gear, activeGear, nextGearSerial, damage);
    }

    private static Map<ShopOffer, Integer> add(Map<ShopOffer, Integer> counts, ShopOffer offer, int delta) {
        var copy = new EnumMap<ShopOffer, Integer>(ShopOffer.class); copy.putAll(counts);
        int value = Math.addExact(copy.getOrDefault(offer, 0), delta);
        if (value < 0) throw new IllegalStateException("Offer count underflow");
        copy.put(offer, value);
        return copy;
    }
}
