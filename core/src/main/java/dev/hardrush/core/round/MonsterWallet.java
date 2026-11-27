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
 * @param bought       purchases since the last sortie ended; drives the doubling price ladder. Permanent
 *                     upgrades stay here for the whole monster life and are never queued in {@code order}
 * @param order        paid goods waiting for the next sortie, including unopened vault keys
 * @param active       consumables frozen by the current deployment
 * @param gear         vault results waiting for a role that can wear them; of each slot's pieces the one with the
 *                     newest serial is worn (see {@link #wearGear})
 * @param activeGear   vault results carried by the current deployment
 * @param sortieDamage per-human damage credit of the current or most recently ended deployment
 * @param parked       the platform's encoded body of a sortie paused by a deliberate return, empty when
 *                     none; a resumed deployment keeps it until the sortie parks again or ends
 * @param creeperTicket one paid creeper sortie and its grade; spent when the sortie truly ends and kept, grade and
 *                      all, on a deliberate return, a cancelled deployment, a disconnect or a change of role
 * @param sortieInvestment milli-coins actually paid for what the current or most recently ended deployment carries
 *                     (issue #106), so a round-long upgrade's tenth is exact; like {@code sortieDamage} it is reset
 *                     only by the next fresh deployment, so a slain body's worth can still read it
 */
public record MonsterWallet(int coins, boolean roleChosen,
                            Map<ShopOffer, Integer> bought, Map<ShopOffer, Integer> order,
                            Map<ShopOffer, Integer> active, List<GearPiece> gear, List<GearPiece> activeGear,
                            int nextGearSerial, Map<UUID, DamageCredit> sortieDamage, String parked, CreeperTicket creeperTicket,
                            long sortieInvestment) {
    public static final int MAX_GEAR = 16;
    public static final int MAX_GEAR_TEXT = 8192;
    public static final int MAX_DAMAGE_VICTIMS = 256;
    public static final int MAX_PARKED_TEXT = 65_536;
    public static final MonsterWallet EMPTY = new MonsterWallet(0, false, Map.of(), Map.of(), Map.of(),
            List.of(), List.of(), 0, Map.of());

    public enum GearSlot { HEAD, CHEST, LEGS, FEET, MAINHAND }

    /**
     * {@code item} is the platform's encoded native stack; core never interprets it. {@code paid} is the whole coins
     * the key that opened it cost, counted as investment by the sortie that wears it. {@code serial} is unique within
     * the wallet and orders the pieces by when they were opened or last put on.
     */
    public record GearPiece(int serial, GearSlot slot, Set<MonsterRole> roles, String item, int paid) {
        public GearPiece {
            Objects.requireNonNull(slot); Objects.requireNonNull(item);
            roles = Set.copyOf(roles);
            if (serial < 0 || roles.isEmpty() || item.isEmpty() || item.length() > MAX_GEAR_TEXT || paid < 0) {
                throw new IllegalArgumentException("Invalid vault gear");
            }
        }

        /** A piece opened before keys were counted as investment. */
        public GearPiece(int serial, GearSlot slot, Set<MonsterRole> roles, String item) {
            this(serial, slot, roles, item, 0);
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
        Objects.requireNonNull(parked); Objects.requireNonNull(creeperTicket);
        if (coins < 0 || nextGearSerial < 0 || gear.size() + activeGear.size() > MAX_GEAR
                || sortieDamage.size() > MAX_DAMAGE_VICTIMS || parked.length() > MAX_PARKED_TEXT || sortieInvestment < 0) {
            throw new IllegalArgumentException("Invalid monster wallet");
        }
        if (active.keySet().stream().anyMatch(ShopOffer::key)) {
            throw new IllegalArgumentException("Vault keys are never carried into a sortie");
        }
    }

    /** A wallet with no paused sortie, as stored before deliberate returns could pause one. */
    public MonsterWallet(int coins, boolean roleChosen, Map<ShopOffer, Integer> bought, Map<ShopOffer, Integer> order,
                         Map<ShopOffer, Integer> active, List<GearPiece> gear, List<GearPiece> activeGear,
                         int nextGearSerial, Map<UUID, DamageCredit> sortieDamage) {
        this(coins, roleChosen, bought, order, active, gear, activeGear, nextGearSerial, sortieDamage, "", CreeperTicket.NONE, 0);
    }

    /** A wallet without a paid creeper ticket. */
    public MonsterWallet(int coins, boolean roleChosen, Map<ShopOffer, Integer> bought, Map<ShopOffer, Integer> order,
                         Map<ShopOffer, Integer> active, List<GearPiece> gear, List<GearPiece> activeGear,
                         int nextGearSerial, Map<UUID, DamageCredit> sortieDamage, String parked) {
        this(coins, roleChosen, bought, order, active, gear, activeGear, nextGearSerial, sortieDamage, parked, CreeperTicket.NONE, 0);
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
    public boolean hasParkedBody() { return !parked.isEmpty(); }

    private MonsterWallet with(int newCoins, boolean chosen, Map<ShopOffer, Integer> newBought,
                               Map<ShopOffer, Integer> newOrder, Map<ShopOffer, Integer> newActive,
                               List<GearPiece> newGear, List<GearPiece> newActiveGear, int serial,
                               Map<UUID, DamageCredit> damage) {
        return new MonsterWallet(newCoins, chosen, newBought, newOrder, newActive, newGear, newActiveGear,
                serial, damage, parked, creeperTicket, sortieInvestment);
    }

    private MonsterWallet withParked(String body) {
        return new MonsterWallet(coins, roleChosen, bought, order, active, gear, activeGear,
                nextGearSerial, sortieDamage, body, creeperTicket, sortieInvestment);
    }

    private MonsterWallet withInvestment(long investment) {
        return new MonsterWallet(coins, roleChosen, bought, order, active, gear, activeGear,
                nextGearSerial, sortieDamage, parked, creeperTicket, investment);
    }

    private MonsterWallet withTicket(int newCoins, CreeperTicket ticket, long investment) {
        return new MonsterWallet(newCoins, roleChosen, bought, order, active, gear, activeGear,
                nextGearSerial, sortieDamage, parked, ticket, investment);
    }

    public MonsterWallet credit(int amount) {
        if (amount < 0) throw new IllegalArgumentException("Negative coin credit");
        if (amount == 0) return this;
        return with((int) Math.min(Integer.MAX_VALUE, (long) coins + amount), roleChosen, bought,
                order, active, gear, activeGear, nextGearSerial, sortieDamage);
    }

    /** Transfer only the coins; purchase limits, queued goods and damage accounting stay intact. */
    public MonsterWallet spendCoins(int amount) {
        if (amount <= 0 || amount > coins) throw new IllegalArgumentException("怪物币数量不足或无效。");
        return with(coins - amount, roleChosen, bought, order, active, gear, activeGear, nextGearSerial, sortieDamage);
    }

    /** Every new monster life starts with the gift; revival already emptied the previous wallet. */
    public MonsterWallet becameMonster(int starter) {
        var wallet = credit(starter).useCreeperTicket();
        return wallet.with(wallet.coins, false, bought, order, active, gear, activeGear, nextGearSerial, sortieDamage);
    }

    public MonsterWallet unlockCreeper(int price) {
        if (creeperTicket.unlocked()) throw new IllegalStateException("已经持有苦力怕出击资格。");
        if (price < 0) throw new IllegalArgumentException("苦力怕解锁价格不能为负数。");
        if (coins < price) throw new Refusal.Exception(Refusal.NOT_ENOUGH_COINS, "怪物币不足：需要 " + price + "，现有 " + coins + "。");
        return withTicket(coins - price, CreeperTicket.PLAIN, sortieInvestment);
    }

    /**
     * Pays to charge an unlocked creeper ticket. Bought while a creeper sortie is paused, the charge rides the parked
     * body's resumption, which counts no {@linkplain #onceInvestment once-per-sortie investment}: it is counted now.
     */
    public MonsterWallet chargeCreeper(RulesConfig rules, int price) {
        if (!creeperTicket.unlocked()) throw new IllegalStateException("请先解锁苦力怕。");
        if (creeperTicket.charged()) throw new IllegalStateException("苦力怕已经带电。");
        if (price < 0) throw new IllegalArgumentException("苦力怕带电价格不能为负数。");
        if (coins < price) throw new Refusal.Exception(Refusal.NOT_ENOUGH_COINS, "怪物币不足：需要 " + price + "，现有 " + coins + "。");
        long investment = hasParkedBody() && MonsterWorth.spoilsEnabled(rules)
                ? Math.addExact(sortieInvestment, Math.multiplyExact(price, 1000L)) : sortieInvestment;
        return withTicket(coins - price, CreeperTicket.CHARGED, investment);
    }

    /** Lightning charges an unlocked creeper for free, as it powers a native one; any other ticket is unchanged. */
    public MonsterWallet struckByLightning() {
        return creeperTicket == CreeperTicket.PLAIN ? withTicket(coins, CreeperTicket.CHARGED, sortieInvestment) : this;
    }

    public MonsterWallet useCreeperTicket() {
        if (creeperTicket == CreeperTicket.NONE) return this;
        return withTicket(coins, CreeperTicket.NONE, sortieInvestment);
    }

    public int nextPrice(RulesConfig rules, ShopOffer offer) {
        return MonsterCoins.price(rules, offer, bought(offer));
    }

    public boolean soldOut(RulesConfig rules, ShopOffer offer) {
        return bought(offer) >= MonsterCoins.limit(rules, offer);
    }

    public MonsterWallet buy(RulesConfig rules, MonsterRole role, ShopOffer offer, int expectedPrice) {
        Objects.requireNonNull(role); Objects.requireNonNull(offer);
        // Revival supplies serve the human life to come, whichever monster bought them.
        if (!offer.revival() && !roleChosen) throw new IllegalStateException("请先在展位选择本次要化身的怪物。");
        if (!offer.revival() && !offer.sellableTo(role)) throw new IllegalStateException("女巫不向当前角色出售" + offer.title() + "。");
        if (!MonsterCoins.offered(rules, offer)) throw new IllegalStateException("女巫本轮不出售" + offer.title() + "。");
        if (soldOut(rules, offer)) throw new IllegalStateException(offer.title() + (offer.permanent() ? "已升到顶。" : offer.revival() ? "已经买满。" : "本次出场已售罄。"));
        int price = nextPrice(rules, offer);
        if (price != expectedPrice) throw new IllegalStateException("价格已变化，请重新选择交易。");
        if (coins < price) throw new Refusal.Exception(Refusal.NOT_ENOUGH_COINS, "怪物币不足：需要 " + price + "，现有 " + coins + "。");
        return with(coins - price, roleChosen, add(bought, offer, 1), offer.permanent() ? order : add(order, offer, 1), active,
                gear, activeGear, nextGearSerial, sortieDamage);
    }

    /** Steps of a round-long upgrade bought so far. */
    public int owned(ShopOffer offer) {
        if (!offer.permanent()) throw new IllegalArgumentException("Not a permanent offer");
        return bought(offer);
    }

    /**
     * A committed role choice; goods the new role cannot use are refunded at the price paid. Revival supplies count as
     * such goods, but a totem holder cannot choose a role and a totem is only taken away when the round ends, so in
     * play they are never refunded here: they go with the revival, or expire with the round.
     */
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

    public MonsterWallet openVault(RulesConfig rules, ShopOffer key, GearSlot slot, Set<MonsterRole> roles, String item) {
        if (!key.key()) throw new IllegalArgumentException("Not a vault key");
        if (ordered(key) <= 0) throw new IllegalStateException("没有可用的" + key.title() + "。");
        if (gearFull()) throw new IllegalStateException("营地装备已满 " + MAX_GEAR + " 件，请先出场使用。");
        var newGear = new ArrayList<>(gear);
        // Keys are always flat-priced (ShopOffer#flatPrice), so the piece's price does not depend on which key opened it.
        newGear.add(new GearPiece(nextGearSerial, slot, roles, item, MonsterCoins.price(rules, key, 0)));
        return with(coins, roleChosen, bought, add(order, key, -1), active, newGear, activeGear,
                Math.addExact(nextGearSerial, 1), sortieDamage);
    }

    /**
     * The camp monster put {@code serial} on: of each slot's pieces the newest serial is the one worn, in the camp and
     * on the next sortie, so the chosen piece takes the next serial and becomes that newest.
     */
    public MonsterWallet wearGear(int serial) {
        var newGear = new ArrayList<>(gear);
        var chosen = newGear.stream().filter(piece -> piece.serial() == serial).findFirst()
                .orElseThrow(() -> new IllegalStateException("营地里没有这件装备。"));
        if (chosen.slot() == GearSlot.MAINHAND) throw new IllegalArgumentException("Only armour is worn");
        newGear.remove(chosen);
        newGear.add(new GearPiece(nextGearSerial, chosen.slot(), chosen.roles(), chosen.item(), chosen.paid()));
        return with(coins, roleChosen, bought, order, active, newGear, activeGear,
                Math.addExact(nextGearSerial, 1), sortieDamage);
    }

    /**
     * Unopened keys are refunded; the role's goods and wearable gear are frozen for this sortie.
     * Resuming a paused sortie freezes only what was bought since it paused, since the parked body
     * already holds the rest, and keeps the sortie's damage credit and investment.
     *
     * <p>Investment (issue #106) counts what was actually paid for what this deployment freezes, and on a fresh
     * deployment also the creeper's unlock and a {@link MonsterWorth#PERMANENT_PERMILLE share} of the role's round-long
     * upgrades. Revival supplies never ride a sortie and gifted eggs or armour never pass through the wallet, so neither
     * counts.
     */
    public MonsterWallet deploy(RulesConfig rules, MonsterRole role) {
        var wallet = refund(rules, ShopOffer::key);
        var carriedGear = wallet.gear.stream().filter(piece -> piece.wearableBy(role)).toList();
        var keptGear = wallet.gear.stream().filter(piece -> !piece.wearableBy(role)).toList();
        var carried = new EnumMap<ShopOffer, Integer>(ShopOffer.class);
        var kept = new EnumMap<ShopOffer, Integer>(ShopOffer.class);
        wallet.order.forEach((offer, count) -> (offer.sellableTo(role) ? carried : kept).put(offer, count));
        boolean fresh = !hasParkedBody();
        var deployed = with(wallet.coins, roleChosen, wallet.bought, kept, carried, keptGear, carriedGear,
                nextGearSerial, fresh ? Map.of() : sortieDamage);
        if (!MonsterWorth.spoilsEnabled(rules)) return deployed.withInvestment(0);
        long investment = Math.addExact(fresh ? 0 : sortieInvestment, deployed.frozenInvestment(rules));
        if (fresh) investment = Math.addExact(investment, deployed.onceInvestment(rules, role));
        return deployed.withInvestment(investment);
    }

    /**
     * Milli-coins paid for the goods and gear this deployment froze. What rides a sortie is always the last of its
     * kind bought (deployed goods leave {@code order}, refunds unwind the latest first, and {@link #endSortie()} keeps
     * ladder steps only for goods still waiting), so the top {@code carried} steps of the ladder are what was paid.
     */
    private long frozenInvestment(RulesConfig rules) {
        long milli = 0;
        for (var entry : active.entrySet()) {
            int had = bought(entry.getKey());
            int paid = MonsterCoins.refund(rules, entry.getKey(), had, Math.min(entry.getValue(), had));
            milli = Math.addExact(milli, Math.multiplyExact(paid, 1000L));
        }
        for (var piece : activeGear) milli = Math.addExact(milli, Math.multiplyExact(piece.paid(), 1000L));
        return milli;
    }

    /**
     * Milli-coins counted once per fresh sortie: the creeper's unlock and charge, and a share of the role's round-long
     * upgrades.
     */
    private long onceInvestment(RulesConfig rules, MonsterRole role) {
        long milli = 0;
        if (role == MonsterRole.CREEPER && creeperTicket.unlocked() && MonsterCoins.creeperPaid(rules)) {
            milli = Math.multiplyExact(MonsterCoins.creeperUnlockPrice(rules), 1000L);
            // A charge from lightning counts at the booth's price too, on purpose: worth reflects the threat a charged
            // creeper is, not what its owner happened to pay for the charge.
            if (creeperTicket.charged() && MonsterCoins.creeperChargeable(rules)) {
                milli = Math.addExact(milli, Math.multiplyExact(MonsterCoins.creeperChargePrice(rules), 1000L));
            }
        }
        int permille = MonsterWorth.amount(rules, MonsterWorth.PERMANENT_PERMILLE);
        for (var offer : ShopOffer.values()) {
            if (!offer.permanent() || !offer.sellableTo(role)) continue;
            int paid = MonsterCoins.refund(rules, offer, bought(offer), bought(offer));
            milli = Math.addExact(milli, Math.multiplyExact((long) paid, permille));
        }
        return milli;
    }

    /**
     * Compensation for an outing that never opened: everything frozen returns to the camp. A cancelled fresh
     * deployment counts no investment; a cancelled resumption takes back only what it froze, measured before the
     * goods return to {@code order}.
     */
    public MonsterWallet cancelDeploy(RulesConfig rules) {
        // A resumed outing not yet landed when it was read from schema 18 recorded no investment (0), so taking back what
        // it froze would go negative: clamp to 0.
        long investment = !hasParkedBody() || !MonsterWorth.spoilsEnabled(rules) ? 0
                : Math.max(0, sortieInvestment - frozenInvestment(rules));
        var restored = new EnumMap<ShopOffer, Integer>(ShopOffer.class); restored.putAll(order);
        active.forEach((offer, count) -> restored.merge(offer, count, Integer::sum));
        var restoredGear = new ArrayList<>(gear); restoredGear.addAll(activeGear);
        restoredGear.sort(Comparator.comparingInt(GearPiece::serial));
        return with(coins, roleChosen, bought, restored, Map.of(), restoredGear, List.of(),
                nextGearSerial, hasParkedBody() ? sortieDamage : Map.of()).withInvestment(investment);
    }

    /**
     * A deliberate return pauses the sortie: {@code body} now holds everything carried, while the
     * price ladder and per-human damage credit continue until the sortie truly ends.
     */
    public MonsterWallet park(String body) {
        if (Objects.requireNonNull(body).isEmpty()) throw new IllegalArgumentException("Empty parked body");
        return new MonsterWallet(coins, roleChosen, bought, order, Map.of(), gear, List.of(),
                nextGearSerial, sortieDamage, body, creeperTicket, sortieInvestment);
    }

    /**
     * Death, disconnect, recall or an abandoned pause: the carried goods are spent, any parked body
     * is discarded and the price ladder restarts. The
     * sortie's damage credit stays until the next deployment, so late buffered damage keeps its cap.
     */
    public MonsterWallet endSortie() {
        // Goods still waiting in the camp keep their paid ladder steps for an exact refund; round-long upgrades stay owned.
        var waiting = new EnumMap<ShopOffer, Integer>(ShopOffer.class);
        bought.forEach((offer, count) -> { if (order.containsKey(offer) || offer.permanent()) waiting.put(offer, count); });
        return new MonsterWallet(coins, roleChosen, waiting, order, Map.of(), gear, List.of(), nextGearSerial, sortieDamage,
                "", creeperTicket, sortieInvestment);
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
