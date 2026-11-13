package dev.hardrush.monster;

import dev.hardrush.core.round.MonsterCoins;
import dev.hardrush.core.round.ShopOffer;
import dev.hardrush.protection.ProtectionRuntime;
import dev.hardrush.combat.CombatRuntime;
import dev.hardrush.core.api.DamagePort.AttackKind;
import dev.hardrush.core.combat.CombatOrigin;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.TypedEntityData;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.BlockHitResult;

/**
 * A zombie's soulcatcher: a native bundle that folds a creature into its native spawn egg and lets it
 * out again. The bundle's own weight is the capacity, since each egg stacks only up to it. Eggs never
 * leave the bag; it releases the selected one directly, like the egg itself would.
 */
public final class MonsterSoulcatcher {
    public static final String PURPOSE = "soulcatcher";
    private static final String SOUL = "soul";
    static final int TIERS = 4;
    private static final String[] TIER_NAMES = {"普通", "罕见", "稀有", "史诗"};
    private static final DyeColor[] BAGS = {DyeColor.WHITE, DyeColor.YELLOW, DyeColor.CYAN, DyeColor.PURPLE};
    private static final Rarity[] RARITIES = {Rarity.COMMON, Rarity.UNCOMMON, Rarity.RARE, Rarity.EPIC};
    /** Goal-driven hostiles that follow and fight for their owner, by the tier that first catches them. */
    private static final List<Set<String>> COMPANIONS = List.of(
            Set.of("zombie", "husk", "drowned", "zombie_villager"),
            Set.of("skeleton", "stray", "bogged", "parched", "spider", "cave_spider", "silverfish", "endermite", "slime", "magma_cube"),
            Set.of("creeper", "witch", "pillager", "vindicator", "wither_skeleton", "blaze"),
            Set.of("evoker", "ravager"));
    /**
     * Released as ordinary native creatures: fliers and hostiles that cannot walk after their owner,
     * brain-driven species whose native AI ignores goals, bosses, and every neutral or passive mob.
     */
    private static final List<Set<String>> ORDINARY = List.of(
            Set.of(),
            Set.of("phantom"),
            Set.of("zombified_piglin", "enderman", "shulker", "guardian", "creaking", "piglin", "hoglin", "zoglin"),
            Set.of("ghast", "vex", "wither", "warden", "elder_guardian", "piglin_brute", "breeze"));
    private static final String[] TIER_LORE = {
            "普通：僵尸、尸壳、溺尸、僵尸村民",
            "罕见：骷髅类、蜘蛛类、蠹虫、末影螨、史莱姆、岩浆怪、幻翼",
            "稀有：苦力怕、女巫、掠夺者、卫道士、凋灵骷髅、烈焰人、猪灵、疣猪兽，以及中立与被动生物",
            "史诗：唤魔者、劫掠兽、猪灵蛮兵、旋风人、恶魂、恼鬼、凋灵、监守者、远古守卫者"};
    /** Position, identity and attachments of the caught body; the release gives it fresh ones. */
    private static final List<String> TRANSIENT = List.of("UUID", "Pos", "Motion", "Rotation", "Passengers", "leash",
            "fall_distance", "FallDistance", "OnGround", "hardrush_monster_source", "hardrush_combat_origin");
    /**
     * Which release of which outing let this creature out: {@code owner/number/binding}. Descendants of a
     * companion carry their ancestor's release; caught again and let out, a creature takes a new number.
     */
    private static final String RELEASE = "hardrush.soul-release=";
    /** The outing's binding and the number of its latest release, per owner. */
    private record Releases(String binding, long latest) {}
    private record Release(UUID owner, long number, String binding) {}
    private static final Map<UUID, Releases> RELEASES = new HashMap<>();

    private MonsterSoulcatcher() {}

    /** The catch rule of one species: the tier that first holds it and whether it fights for its owner. */
    public record Catch(int tier, boolean companion) {}

    /** Empty for the ender dragon, the round's win condition, and anything that is not a mob. */
    public static Optional<Catch> rule(EntityType<?> type) {
        if (type == net.minecraft.world.entity.EntityTypes.ENDER_DRAGON) return Optional.empty();
        String id = EntityType.getKey(type).getPath();
        for (int tier = 0; tier < TIERS; tier++) {
            if (COMPANIONS.get(tier).contains(id)) return Optional.of(new Catch(tier, true));
            if (ORDINARY.get(tier).contains(id)) return Optional.of(new Catch(tier, false));
        }
        return Optional.empty();
    }

    private static Optional<Catch> rule(Mob mob) {
        if (mob instanceof EnderDragon) return Optional.empty();
        // An unlisted hostile counts as elite; every other unlisted creature is neutral or passive.
        return rule(mob.getType()).or(() -> Optional.of(new Catch(mob instanceof Enemy ? 3 : 2, false)));
    }

    public static boolean companionType(EntityType<?> type) { return rule(type).map(Catch::companion).orElse(false); }

    public static boolean isBag(ItemStack stack) { return MonsterItems.purpose(stack).equals(PURPOSE); }

    public static boolean holds(ServerPlayer player, InteractionHand hand) {
        return MonsterItems.matches(player, player.getItemInHand(hand), PURPOSE);
    }

    /** Tier and capacity this deployment's soulcatcher has, from the round's rules and the witch's upgrades. */
    private static int tier(ServerPlayer player) {
        return Math.min(TIERS - 1, MonsterRoundRuntime.sortieKit(player).bought(ShopOffer.SOULCATCHER_TIER));
    }
    private static int capacity(ServerPlayer player) {
        var rules = MonsterRoundRuntime.rules(player);
        int base = rules.filter(MonsterCoins::soulcatcher).map(r -> MonsterCoins.amount(r, MonsterCoins.SOULCATCHER_CAPACITY)).orElse(3);
        return Math.max(1, Math.min(99, base + MonsterRoundRuntime.sortieKit(player).bought(ShopOffer.SOULCATCHER_CAPACITY)));
    }

    /** How full the held bag is and both of its uses, on the action bar while held. */
    public static Optional<String> heldHint(ServerPlayer player, ItemStack stack) {
        if (!stack.has(DataComponents.BUNDLE_CONTENTS) || !MonsterItems.matches(player, stack, PURPOSE)) return Optional.empty();
        int held = stack.getOrDefault(DataComponents.BUNDLE_CONTENTS, BundleContents.EMPTY).size();
        return Optional.of("袋中 " + dev.hardrush.notice.Notices.key(held + "/" + capacity(player)) + " · 右键生物收编，右键方块或水面放出");
    }

    /** Whether this round issues a soulcatcher; development fixtures outside a round always carry one. */
    public static boolean enabled(ServerPlayer player) {
        return MonsterRoundRuntime.rules(player).map(MonsterCoins::soulcatcher).orElse(true);
    }

    /** A fresh, empty bag for a new zombie sortie. */
    public static ItemStack bag(ServerPlayer player) { return bag(player, tier(player), capacity(player), List.of()); }

    /**
     * The bag a paused sortie resumes with, or one left in the inventory before an upgrade: the same
     * creatures in the same order, at this deployment's tier and capacity.
     */
    public static ItemStack refresh(ServerPlayer player, ItemStack old) {
        var contents = old.getOrDefault(DataComponents.BUNDLE_CONTENTS, BundleContents.EMPTY);
        return bag(player, tier(player), capacity(player), contents.itemCopies().toList());
    }

    private static ItemStack bag(ServerPlayer player, int tier, int capacity, List<ItemStack> eggs) {
        var stack = new ItemStack(Items.DYED_BUNDLE.pick(BAGS[tier]));
        stack.set(DataComponents.ITEM_NAME, Component.literal(TIER_NAMES[tier] + "拘魂袋"));
        stack.set(DataComponents.RARITY, RARITIES[tier]);
        var lore = new ArrayList<Component>();
        lore.add(line("右键生物：收编为刷怪蛋装进袋里，最多 " + capacity + " 只", ChatFormatting.GRAY));
        lore.add(line("右键方块或水面：放出袋中选中的那只；蛋不能取出", ChatFormatting.GRAY));
        lore.add(line("在背包里悬停袋子滚动滚轮，可以挑选要放出的那只", ChatFormatting.GRAY));
        for (int i = 0; i <= tier; i++) lore.add(line(TIER_LORE[i], ChatFormatting.DARK_AQUA));
        if (tier + 1 < TIERS) lore.add(line("女巫商人可升阶为" + TIER_NAMES[tier + 1] + "拘魂袋", ChatFormatting.DARK_GRAY));
        lore.add(line("不能收编末影龙和别人的随从", ChatFormatting.DARK_GRAY));
        stack.set(DataComponents.LORE, new ItemLore(lore));
        var bag = new BundleContents.Mutable();
        // tryInsert puts each egg first, so the oldest goes in first to keep the order.
        for (var egg : eggs.reversed()) bag.tryInsert(MonsterItems.bind(player, sized(egg, capacity), SOUL));
        stack.set(DataComponents.BUNDLE_CONTENTS, bag.toImmutable());
        return MonsterItems.bind(player, stack, PURPOSE);
    }

    private static ItemStack sized(ItemStack egg, int capacity) {
        var copy = egg.copy();
        copy.set(DataComponents.MAX_STACK_SIZE, capacity);
        return copy;
    }

    private static Component line(String text, ChatFormatting color) {
        return Component.literal(text).withStyle(Style.EMPTY.withItalic(false).withColor(color));
    }

    /**
     * Right-click on a creature. Folds it into the bag, or returns a zombie hatched from the role's
     * own egg to that egg.
     */
    public static boolean capture(ServerPlayer player, InteractionHand hand, Entity target) {
        var bag = player.getItemInHand(hand);
        if (!MonsterItems.matches(player, bag, PURPOSE) || !MonsterRoundRuntime.canAct(player)
                || target.isRemoved() || !(target instanceof LivingEntity living) || !living.isAlive()) return false;
        if (!(target instanceof Mob mob) || MonsterRoles.isNativeBody(mob)) { dev.hardrush.notice.Notices.refuse(player, "只能收编生物"); return false; }
        if (ProtectionRuntime.protectedEntity(mob) || ProtectionRuntime.protectedEntity(player)) {
            dev.hardrush.notice.Notices.refuse(player, "保护区里的生物不能收编"); return false;
        }
        var owner = MonsterCompanions.owner(mob);
        if (owner != null && (!owner.equals(player.getUUID()) || MonsterCompanions.isDismissing(mob))) {
            dev.hardrush.notice.Notices.refuse(player, "这是别人的随从"); return false;
        }
        dev.hardrush.lobby.LobbyRuntime.interruptReturn(player, "使用物品");
        var hatched = owner == null ? Optional.<Boolean>empty() : MonsterCompanions.hatchedBaby(mob);
        if (hatched.isPresent()) {
            if (!MonsterRoles.returnEgg(player, hatched.get())) { dev.hardrush.notice.Notices.refuse(player, "背包满了，放不下僵尸蛋"); return false; }
            vanish(player, mob);
            return true;
        }
        var rule = rule(mob).orElse(null);
        if (rule == null) { dev.hardrush.notice.Notices.refuse(player, dev.hardrush.notice.Notices.key(mob.getName().getString()) + " 不能被收编"); return false; }
        int tier = tier(player);
        if (rule.tier() > tier) {
            dev.hardrush.notice.Notices.refuse(player, "需要 " + dev.hardrush.notice.Notices.key(TIER_NAMES[rule.tier()]) + " 拘魂袋才能收编 " + dev.hardrush.notice.Notices.key(mob.getName().getString())); return false;
        }
        var item = SpawnEggItem.byId(mob.getType()).orElse(null);
        if (item == null) { dev.hardrush.notice.Notices.refuse(player, dev.hardrush.notice.Notices.key(mob.getName().getString()) + " 不能被收编"); return false; }
        int capacity = capacity(player);
        var egg = MonsterItems.bind(player, sized(egg(player, mob, item.value()), capacity), SOUL);
        var contents = bag.getOrDefault(DataComponents.BUNDLE_CONTENTS, BundleContents.EMPTY).asMutable();
        if (contents.tryInsert(egg) == 0) { dev.hardrush.notice.Notices.refuse(player, "拘魂袋满了（" + dev.hardrush.notice.Notices.key(capacity + "/" + capacity) + "）"); return false; }
        bag.set(DataComponents.BUNDLE_CONTENTS, contents.toImmutable());
        vanish(player, mob);
        return true;
    }

    /** The caught body as its native egg; the egg name tells the bag's tooltip apart from another of the kind. */
    static ItemStack egg(ServerPlayer player, Mob mob, Item item) {
        var output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, player.registryAccess());
        mob.saveWithoutId(output);
        CompoundTag data = output.buildResult();
        TRANSIENT.forEach(data::remove);
        var stack = new ItemStack(item);
        stack.set(DataComponents.ENTITY_DATA, TypedEntityData.of(mob.getType(), data));
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("收编的" + mob.getName().getString())
                .withStyle(Style.EMPTY.withItalic(false)));
        boolean companion = rule(mob).map(Catch::companion).orElse(false);
        stack.set(DataComponents.LORE, new ItemLore(List.of(
                line("生命值 " + Math.round(mob.getHealth()) + "/" + Math.round(mob.getMaxHealth()), ChatFormatting.GRAY),
                line(companion ? "放出后成为随从：跟随并协助你攻击" : "放出后是普通生物，按原版行动", ChatFormatting.GRAY))));
        return stack;
    }

    private static void vanish(ServerPlayer player, Mob mob) {
        var level = player.level();
        // Native removal keeps neither a leash nor riders; they come loose the way a dying mob lets go.
        if (mob.isLeashed()) mob.dropLeash();
        mob.ejectPassengers(); mob.stopRiding();
        level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.BUNDLE_INSERT, SoundSource.PLAYERS, 1, 0.8F);
        level.sendParticles(ParticleTypes.SOUL, mob.getX(), mob.getY(0.5), mob.getZ(), 12,
                mob.getBbWidth() / 3, mob.getBbHeight() / 3, mob.getBbWidth() / 3, 0.02);
        mob.discard();
        player.inventoryMenu.broadcastChanges();
    }

    /** Right-click on a block: the selected creature lands on the clicked face. */
    public static boolean releaseOn(ServerPlayer player, InteractionHand hand, BlockHitResult hit) {
        if (!holds(player, hand) || !MonsterRoundRuntime.canAct(player)) return false;
        var landing = MonsterCompanions.landingOn(player, hit);
        return landing != null && release(player, hand, landing);
    }

    /** Right-click into the air: only a liquid source along the view takes the creature, as with a native egg. */
    public static boolean releaseInLiquid(ServerPlayer player, InteractionHand hand) {
        if (!holds(player, hand) || !MonsterRoundRuntime.canAct(player)) return false;
        var landing = MonsterCompanions.landingInLiquid(player);
        return landing != null && release(player, hand, landing);
    }

    private static boolean release(ServerPlayer player, InteractionHand hand, MonsterCompanions.Landing landing) {
        var bag = player.getItemInHand(hand);
        var contents = bag.getOrDefault(DataComponents.BUNDLE_CONTENTS, BundleContents.EMPTY);
        if (contents.isEmpty()) { dev.hardrush.notice.Notices.refuse(player, "拘魂袋是空的 · 右键生物收编"); return false; }
        dev.hardrush.lobby.LobbyRuntime.interruptReturn(player, "使用物品");
        var mutable = contents.asMutable();
        var stack = mutable.removeOne();
        var egg = stack.split(1);
        var data = egg.get(DataComponents.ENTITY_DATA);
        var level = player.level();
        Entity created = data == null ? null : data.type().create(level, EntitySpawnReason.SPAWN_ITEM_USE);
        if (!(created instanceof Mob mob)) {
            if (created != null) created.discard();
            dev.hardrush.notice.Notices.refuse(player, "这枚蛋坏了，放不出来"); return false;
        }
        data.loadInto(mob);
        if (!MonsterCompanions.place(player, mob, landing)) { mob.discard(); dev.hardrush.notice.Notices.refuse(player, "这里放不下 " + dev.hardrush.notice.Notices.key(mob.getName().getString())); return false; }
        if (rule(mob).map(Catch::companion).orElse(false)) MonsterCompanions.adopt(player, mob);
        else MonsterCompanions.release(mob);
        mob.setPersistenceRequired();
        var binding = MonsterRoles.binding(player);
        var previous = RELEASES.get(player.getUUID());
        var releases = new Releases(binding, previous != null && previous.binding().equals(binding) ? previous.latest() + 1 : 1);
        setRelease(mob, player.getUUID() + "/" + releases.latest() + "/" + binding);
        if (!level.addFreshEntity(mob)) { mob.discard(); return false; }
        RELEASES.put(player.getUUID(), releases);
        if (!stack.isEmpty()) mutable.tryInsert(stack);
        bag.set(DataComponents.BUNDLE_CONTENTS, mutable.toImmutable());
        level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.BUNDLE_REMOVE_ONE, SoundSource.PLAYERS, 1, 0.8F);
        level.sendParticles(ParticleTypes.SOUL, mob.getX(), mob.getY(0.5), mob.getZ(), 12,
                mob.getBbWidth() / 3, mob.getBbHeight() / 3, mob.getBbWidth() / 3, 0.02);
        level.gameEvent(player, net.minecraft.world.level.gameevent.GameEvent.ENTITY_PLACE, landing.pos());
        player.inventoryMenu.broadcastChanges();
        return true;
    }


    /**
     * The revival kill credit of damage a released creature deals. The bag only vouches for the latest
     * releases, as many as it holds now: a creature (or a companion's descendant) among the owner's last
     * {@code capacity} releases of the current outing credits its owner, whether it is a companion or an
     * ordinary creature; an older one, or one whose outing has ended, credits no one. Damage that did not
     * come from a released creature keeps its own provenance.
     */
    public static Optional<CombatOrigin> killCredit(ServerLevel level, DamageSource source, Optional<CombatOrigin> origin) {
        var creature = release(source.getEntity()).isPresent() ? source.getEntity() : source.getDirectEntity();
        var release = release(creature).orElse(null);
        if (release == null) return origin;
        var owner = level.getServer().getPlayerList().getPlayer(release.owner());
        var releases = owner == null ? null : RELEASES.get(owner.getUUID());
        if (owner == null || releases == null || !releases.binding().equals(release.binding())
                || !release.binding().equals(MonsterRoles.binding(owner))
                || !counts(release.number(), releases.latest(), capacity(owner))) return Optional.empty();
        // A companion keeps its sealed creation identity; an ordinary creature borrows its owner's present one.
        return origin.filter(value -> value.actor().playerId().equals(owner.getUUID()))
                .or(() -> CombatRuntime.ownerOrigin(level, owner, AttackKind.SUMMON, Optional.of(creature.getUUID())));
    }

    /** Whether release {@code number} is among the latest {@code capacity} releases up to {@code latest}. */
    static boolean counts(long number, long latest, int capacity) {
        return number > 0 && number <= latest && number > latest - capacity;
    }

    /** A companion's descendant (infection, conversion, split, an evoker's vex) shares its ancestor's release. */
    static void inheritRelease(Entity source, Entity child) {
        var value = source == null ? null : releaseTag(source);
        if (value == null) clearRelease(child);
        else setRelease(child, value);
    }

    static void clearRelease(Entity entity) { entity.entityTags().removeIf(tag -> tag.startsWith(RELEASE)); }

    public static void clear() { RELEASES.clear(); }

    private static void setRelease(Entity entity, String value) {
        clearRelease(entity);
        entity.entityTags().add(RELEASE + value);
    }

    private static String releaseTag(Entity entity) {
        for (String tag : entity.entityTags()) if (tag.startsWith(RELEASE)) return tag.substring(RELEASE.length());
        return null;
    }

    private static Optional<Release> release(Entity entity) {
        var value = entity == null ? null : releaseTag(entity);
        if (value == null) return Optional.empty();
        var fields = value.split("/", 3);
        try {
            return fields.length == 3 ? Optional.of(new Release(UUID.fromString(fields[0]), Long.parseLong(fields[1]), fields[2]))
                    : Optional.empty();
        } catch (IllegalArgumentException malformed) { return Optional.empty(); }
    }
}
