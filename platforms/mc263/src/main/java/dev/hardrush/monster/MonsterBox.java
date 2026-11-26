package dev.hardrush.monster;

import dev.hardrush.core.round.MonsterBox.Result;
import dev.hardrush.notice.Notices;
import java.util.List;
import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.chicken.Chicken;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.BlockHitResult;

/**
 * A zombie's monster box (docs/plan/怪物盲盒.md): a bound spawn egg that lets out a random creature drawn by the
 * soulcatcher's tier. Everything it lets out is the owner's companion, like a hatched egg's zombie: it never aims at
 * its owner, its damage and kills credit the owner, and it dies without loot when the owner's sortie ends.
 */
public final class MonsterBox {
    public static final String PURPOSE = "monster_box";
    private static final String TAGLINE = "升级拘魂袋，它能开出更疯狂的东西";
    private static final String USAGE = "右键方块或水面，放出一只随机生物";
    private static final Rarity[] RARITIES = {Rarity.COMMON, Rarity.UNCOMMON, Rarity.RARE, Rarity.EPIC};
    /** The sculk shrieker's warden search (SculkShriekerBlockEntity → SpawnUtil.trySpawnMob): 20 tries, ±5 across, ±6 up and down. */
    private static final int WARDEN_ATTEMPTS = 20, WARDEN_RANGE_XZ = 5, WARDEN_RANGE_Y = 6;

    private MonsterBox() {}

    /** {@code count} boxes for this deployment, named in the colour of its soulcatcher's rarity. */
    public static ItemStack stack(ServerPlayer player, int count) {
        var stack = new ItemStack(Items.ZOMBIE_SPAWN_EGG, count);
        stack.set(DataComponents.ITEM_NAME, Component.literal("怪物盲盒"));
        stack.set(DataComponents.RARITY, RARITIES[Math.min(MonsterSoulcatcher.tier(player), RARITIES.length - 1)]);
        stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        stack.set(DataComponents.LORE, new ItemLore(List.of(Component.literal(TAGLINE)
                .withStyle(Style.EMPTY.withItalic(false).withColor(ChatFormatting.GRAY)))));
        return MonsterItems.bind(player, stack, PURPOSE);
    }

    public static boolean isBox(ServerPlayer player, ItemStack stack) { return MonsterItems.matches(player, stack, PURPOSE); }

    public static Optional<String> heldHint(ServerPlayer player, ItemStack stack) {
        return isBox(player, stack) ? Optional.of(USAGE) : Optional.empty();
    }

    /** Boxes carried over a pause or an upgrade take this deployment's colour; their count is kept. */
    public static void refresh(ServerPlayer player) {
        var inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            var stack = inventory.getItem(i);
            if (isBox(player, stack)) inventory.setItem(i, stack(player, stack.getCount()));
        }
    }

    /** Right-click on a block: the clicked face, as a native spawn egg picks it. */
    public static boolean useOn(ServerPlayer player, InteractionHand hand, BlockHitResult hit) {
        if (!usable(player, player.getItemInHand(hand))) return false;
        var landing = MonsterCompanions.landingOn(player, hit);
        return landing != null && open(player, hand, landing);
    }

    /** Right-click into the air: only a liquid source along the view takes the creature, as with a native egg. */
    public static boolean use(ServerPlayer player, InteractionHand hand) {
        if (!usable(player, player.getItemInHand(hand))) return false;
        var landing = MonsterCompanions.landingInLiquid(player);
        return landing != null && open(player, hand, landing);
    }

    private static boolean usable(ServerPlayer player, ItemStack stack) {
        return player.isAlive() && MonsterRoundRuntime.canAct(player) && !MonsterRoles.binding(player).isEmpty() && isBox(player, stack);
    }

    /**
     * Where the landing is refused (unloaded, outside the border, protected) the box is kept and nothing is drawn, so a
     * refusal never tells what was inside. Anywhere else the creature comes out without asking whether it fits.
     */
    private static boolean open(ServerPlayer player, InteractionHand hand, MonsterCompanions.Landing landing) {
        if (!MonsterCompanions.landable(player, landing)) { Notices.refuse(player, "这里放不出生物"); return false; }
        dev.hardrush.lobby.LobbyRuntime.interruptChannel(player, "使用物品");
        var result = dev.hardrush.core.round.MonsterBox.draw(MonsterSoulcatcher.tier(player),
                player.getRandom().nextInt(dev.hardrush.core.round.MonsterBox.TOTAL));
        var mob = letOut(player, landing, result);
        if (mob == null) { Notices.refuse(player, "怪物盲盒没能打开"); return false; }
        player.getItemInHand(hand).shrink(1);
        player.inventoryMenu.broadcastChanges();
        var level = player.level();
        level.gameEvent(player, GameEvent.ENTITY_PLACE, landing.pos());
        if (result.notable()) level.playSound(null, mob.getX(), mob.getY(), mob.getZ(), SoundEvents.VAULT_EJECT_ITEM, SoundSource.BLOCKS, 1, 1);
        String told = "开出：" + Notices.key(result.title());
        Notices.hint(player, result == Result.WITHER ? told + " · 正在蓄力，快离开" : told);
        return true;
    }

    /**
     * Builds {@code result} at the landing and lets it out as the owner's companions, riders and mounts alike. Null
     * when the level refuses the creature (e.g. a hostile in peaceful), in which case nothing is left behind.
     */
    static Mob letOut(ServerPlayer player, MonsterCompanions.Landing landing, Result result) {
        var level = player.level();
        Mob root = build(player, landing, result);
        if (root == null) return null;
        var bodies = root.getSelfAndPassengers().toList();
        for (Entity body : bodies) {
            if (!(body instanceof Mob mob)) continue;
            MonsterCompanions.adopt(player, mob);
            mob.setPersistenceRequired();
        }
        if (!level.addFreshEntity(root)) { bodies.forEach(Entity::discard); return null; }
        for (Entity body : bodies) if (body != root) level.addFreshEntity(body);
        return root;
    }

    private static Mob build(ServerPlayer player, MonsterCompanions.Landing landing, Result result) {
        var level = player.level();
        return switch (result) {
            case ZOMBIE, HUSK, DROWNED, ZOMBIE_VILLAGER -> zombie(player, landing, zombieType(result), false);
            case BABY_ZOMBIE -> zombie(player, landing, EntityTypes.ZOMBIE, true);
            case ZOMBIE_HORSEMAN -> {
                // Native zombie horses only bring their spear-armed rider when they spawn naturally (ZombieHorse.finalizeSpawn).
                var horse = placed(player, landing, EntityTypes.ZOMBIE_HORSE, EntitySpawnReason.SPAWN_ITEM_USE, null);
                if (horse == null) yield null;
                var rider = rider(level, horse, EntityTypes.ZOMBIE, new Zombie.ZombieGroupData(false, false));
                if (rider != null) rider.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SPEAR));
                yield horse;
            }
            case CHICKEN_JOCKEY -> {
                // As Zombie.finalizeSpawn makes one: a jockey chicken carrying a baby zombie.
                var chicken = placed(player, landing, EntityTypes.CHICKEN, EntitySpawnReason.JOCKEY, null);
                if (chicken == null) yield null;
                ((Chicken) chicken).setChickenJockey(true);
                var baby = rider(level, chicken, EntityTypes.ZOMBIE, new Zombie.ZombieGroupData(true, false));
                if (baby instanceof Zombie zombie) zombie.setBaby(true);
                yield chicken;
            }
            case SPIDER_JOCKEY -> {
                // As Spider.finalizeSpawn makes one; a spider that already rolled its own native rider keeps it.
                var spider = placed(player, landing, EntityTypes.SPIDER, EntitySpawnReason.SPAWN_ITEM_USE, null);
                if (spider != null && !spider.isVehicle()) rider(level, spider, EntityTypes.SKELETON, null);
                yield spider;
            }
            case WITHER -> {
                // Summoned like the soul-sand build (WitherSkullBlock): charging and invulnerable, then a blast and a global sound.
                var wither = EntityTypes.WITHER.create(level, EntitySpawnReason.TRIGGERED);
                if (wither == null) yield null;
                MonsterCompanions.placeAnyway(player, wither, landing);
                wither.makeInvulnerable();
                yield wither;
            }
            case WARDEN -> warden(player, landing);
            default -> placed(player, landing, simpleType(result), EntitySpawnReason.SPAWN_ITEM_USE, null);
        };
    }

    private static Mob zombie(ServerPlayer player, MonsterCompanions.Landing landing, EntityType<? extends Zombie> type, boolean baby) {
        // Explicit group data keeps the box's own odds: no native baby roll and no native chicken jockey.
        var zombie = (Zombie) placed(player, landing, type, EntitySpawnReason.SPAWN_ITEM_USE, new Zombie.ZombieGroupData(baby, false));
        if (zombie != null) zombie.setBaby(baby);
        return zombie;
    }

    /** A native spawn-egg creature at the landing, finalized like one; null when the level refuses the type. */
    private static Mob placed(ServerPlayer player, MonsterCompanions.Landing landing, EntityType<? extends Mob> type,
                              EntitySpawnReason reason, net.minecraft.world.entity.SpawnGroupData data) {
        var level = player.level();
        var mob = type.create(level, reason);
        if (mob == null) return null;
        MonsterCompanions.placeAnyway(player, mob, landing);
        mob.finalizeSpawn(level, level.getCurrentDifficultyAt(mob.blockPosition()), reason, data);
        return mob;
    }

    private static Mob rider(ServerLevel level, Mob mount, EntityType<? extends Mob> type, net.minecraft.world.entity.SpawnGroupData data) {
        var rider = type.create(level, EntitySpawnReason.JOCKEY);
        if (rider == null) return null;
        rider.snapTo(mount.getX(), mount.getY(), mount.getZ(), mount.getYRot(), 0);
        rider.finalizeSpawn(level, level.getCurrentDifficultyAt(mount.blockPosition()), EntitySpawnReason.JOCKEY, data);
        rider.startRiding(mount, false, false);
        return rider;
    }

    /**
     * The warden digs its way out, as a sculk shrieker summons it: a spot found by the shrieker's own search around the
     * landing, or the landing itself when none is found, so a box is never refused for want of room.
     */
    private static Mob warden(ServerPlayer player, MonsterCompanions.Landing landing) {
        var level = player.level();
        var random = level.getRandom();
        for (int attempt = 0; attempt < WARDEN_ATTEMPTS; attempt++) {
            var pos = landing.pos().offset(Mth.randomBetweenInclusive(random, -WARDEN_RANGE_XZ, WARDEN_RANGE_XZ), WARDEN_RANGE_Y,
                    Mth.randomBetweenInclusive(random, -WARDEN_RANGE_XZ, WARDEN_RANGE_XZ)).mutable();
            if (!level.getWorldBorder().isWithinBounds(pos) || !onTopOfCollider(level, pos)) continue;
            var warden = EntityTypes.WARDEN.create(level, null, pos.immutable(), EntitySpawnReason.TRIGGERED, false, false);
            if (warden == null) return null;
            if (warden.checkSpawnRules(level, EntitySpawnReason.TRIGGERED) && warden.checkSpawnObstruction(level)) return warden;
            warden.discard();
        }
        return placed(player, landing, EntityTypes.WARDEN, EntitySpawnReason.TRIGGERED, null);
    }

    /**
     * SpawnUtil's downward scan with {@code Strategy.ON_TOP_OF_COLLIDER}: the first full-topped block with room above,
     * leaving {@code pos} just above it. Only loaded chunks are read, so the search never loads terrain.
     */
    private static boolean onTopOfCollider(ServerLevel level, BlockPos.MutableBlockPos pos) {
        if (level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) == null) return false;
        var above = new BlockPos.MutableBlockPos().set(pos);
        var aboveState = level.getBlockState(above);
        for (int y = WARDEN_RANGE_Y; y >= -WARDEN_RANGE_Y; y--) {
            pos.move(Direction.DOWN);
            above.setWithOffset(pos, Direction.UP);
            var state = level.getBlockState(pos);
            if (aboveState.getCollisionShape(level, above).isEmpty() && Block.isFaceFull(state.getCollisionShape(level, pos), Direction.UP)) {
                pos.move(Direction.UP);
                return true;
            }
            aboveState = state;
        }
        return false;
    }

    private static EntityType<? extends Zombie> zombieType(Result result) {
        return switch (result) {
            case HUSK -> EntityTypes.HUSK;
            case DROWNED -> EntityTypes.DROWNED;
            case ZOMBIE_VILLAGER -> EntityTypes.ZOMBIE_VILLAGER;
            default -> EntityTypes.ZOMBIE;
        };
    }

    private static EntityType<? extends Mob> simpleType(Result result) {
        return switch (result) {
            case SKELETON -> EntityTypes.SKELETON;
            case SPIDER -> EntityTypes.SPIDER;
            case STRAY -> EntityTypes.STRAY;
            case BOGGED -> EntityTypes.BOGGED;
            case PARCHED -> EntityTypes.PARCHED;
            case CAVE_SPIDER -> EntityTypes.CAVE_SPIDER;
            case SLIME -> EntityTypes.SLIME;
            case MAGMA_CUBE -> EntityTypes.MAGMA_CUBE;
            case CREEPER -> EntityTypes.CREEPER;
            case WITCH -> EntityTypes.WITCH;
            case PILLAGER -> EntityTypes.PILLAGER;
            case VINDICATOR -> EntityTypes.VINDICATOR;
            case WITHER_SKELETON -> EntityTypes.WITHER_SKELETON;
            case BLAZE -> EntityTypes.BLAZE;
            case EVOKER -> EntityTypes.EVOKER;
            case BREEZE -> EntityTypes.BREEZE;
            case PIGLIN_BRUTE -> EntityTypes.PIGLIN_BRUTE;
            case RAVAGER -> EntityTypes.RAVAGER;
            case CHICKEN -> EntityTypes.CHICKEN;
            case PIG -> EntityTypes.PIG;
            case VILLAGER -> EntityTypes.VILLAGER;
            default -> throw new IllegalArgumentException("Not a single creature: " + result);
        };
    }
}
