package me.ryanhamshire.GriefPrevention.catcrafttrust;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Barrel;
import org.bukkit.block.BlastFurnace;
import org.bukkit.block.BrewingStand;
import org.bukkit.block.Campfire;
import org.bukkit.block.Chest;
import org.bukkit.block.ChiseledBookshelf;
import org.bukkit.block.Crafter;
import org.bukkit.block.DecoratedPot;
import org.bukkit.block.Dispenser;
import org.bukkit.block.Dropper;
import org.bukkit.block.Furnace;
import org.bukkit.block.Hopper;
import org.bukkit.block.Jukebox;
import org.bukkit.block.Lectern;
import org.bukkit.block.ShulkerBox;
import org.bukkit.block.Smoker;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.HumanEntity;
import org.bukkit.inventory.Merchant;

import java.util.EnumSet;
import java.util.Set;

/**
 * Fail-closed classification for storage and automation that a safe builder
 * must not mutate. This class retains no Bukkit state.
 */
public final class StorageProtectionPolicy
{
    private static final Set<Material> STORAGE_MATERIALS = EnumSet.of(
            Material.CHEST, Material.TRAPPED_CHEST, Material.BARREL,
            Material.FURNACE, Material.BLAST_FURNACE, Material.SMOKER,
            Material.SHULKER_BOX, Material.WHITE_SHULKER_BOX, Material.ORANGE_SHULKER_BOX,
            Material.MAGENTA_SHULKER_BOX, Material.LIGHT_BLUE_SHULKER_BOX,
            Material.YELLOW_SHULKER_BOX, Material.LIME_SHULKER_BOX, Material.PINK_SHULKER_BOX,
            Material.GRAY_SHULKER_BOX, Material.LIGHT_GRAY_SHULKER_BOX, Material.CYAN_SHULKER_BOX,
            Material.PURPLE_SHULKER_BOX, Material.BLUE_SHULKER_BOX, Material.BROWN_SHULKER_BOX,
            Material.GREEN_SHULKER_BOX, Material.RED_SHULKER_BOX, Material.BLACK_SHULKER_BOX,
            Material.HOPPER, Material.DROPPER, Material.DISPENSER,
            Material.BREWING_STAND, Material.CRAFTER, Material.DECORATED_POT,
            Material.CHISELED_BOOKSHELF, Material.CAMPFIRE, Material.SOUL_CAMPFIRE,
            Material.JUKEBOX, Material.LECTERN);

    private static final Set<Material> AUTOMATION_MATERIALS = EnumSet.of(
            Material.HOPPER, Material.DROPPER, Material.DISPENSER, Material.CRAFTER,
            Material.HOPPER_MINECART, Material.CHEST_MINECART,
            Material.PISTON, Material.STICKY_PISTON, Material.OBSERVER,
            Material.REDSTONE_BLOCK, Material.REDSTONE_TORCH, Material.REDSTONE_WALL_TORCH,
            Material.REPEATER, Material.COMPARATOR, Material.REDSTONE_WIRE, Material.TNT);

    private StorageProtectionPolicy()
    {
    }

    public enum StorageDecision
    {
        ORDINARY,
        EMPTY_BREAKABLE,
        PROTECTED_NONEMPTY,
        AUTOMATION_DENIED,
        AMBIGUOUS_DENIED
    }

    /**
     * Classifies one block at the event location. A failed state or inventory
     * read is always denied so a plugin-specific holder cannot hide contents.
     */
    public static StorageDecision classifyBreak(Block block)
    {
        if (block == null) return StorageDecision.AMBIGUOUS_DENIED;
        final Material material;
        final BlockState state;
        try
        {
            material = block.getType();
            state = block.getState();
        }
        catch (RuntimeException failure)
        {
            return StorageDecision.AMBIGUOUS_DENIED;
        }
        if (material == null || state == null) return StorageDecision.AMBIGUOUS_DENIED;
        if (AUTOMATION_MATERIALS.contains(material)) return StorageDecision.AUTOMATION_DENIED;
        if (material == Material.CAMPFIRE || material == Material.SOUL_CAMPFIRE)
        {
            return classifyCampfire(state);
        }
        if (!STORAGE_MATERIALS.contains(material))
        {
            return state instanceof InventoryHolder
                    ? StorageDecision.AMBIGUOUS_DENIED : StorageDecision.ORDINARY;
        }
        if (!isSupportedBlockState(material, state)) return StorageDecision.AMBIGUOUS_DENIED;
        return classifyKnownStorage(state);
    }

    public static boolean isDirectRedstoneControl(Material material)
    {
        if (material == null) return false;
        String name = material.name();
        return material == Material.LEVER || name.endsWith("_BUTTON")
                || name.endsWith("_PRESSURE_PLATE");
    }

    /**
     * Returns whether a material can be placed by a safe builder. Storage
     * blocks may be placed when their contents are not an automation bypass;
     * extraction and output automation is denied at the material boundary.
     */
    public static boolean denyPlacement(Material material)
    {
        return material != null && AUTOMATION_MATERIALS.contains(material);
    }

    /**
     * Classifies a placed item, including a shulker's detached BlockStateMeta.
     * A metadata read that cannot be proven safe is denied.
     */
    public static StorageDecision classifyPlacement(ItemStack item)
    {
        if (item == null) return StorageDecision.AMBIGUOUS_DENIED;
        final Material material;
        try
        {
            material = item.getType();
        }
        catch (RuntimeException failure)
        {
            return StorageDecision.AMBIGUOUS_DENIED;
        }
        if (material == null) return StorageDecision.AMBIGUOUS_DENIED;
        if (denyPlacement(material)) return StorageDecision.AUTOMATION_DENIED;
        if (!isShulker(material)) return StorageDecision.ORDINARY;
        try
        {
            ItemMeta itemMeta = item.getItemMeta();
            if (itemMeta == null) return StorageDecision.AMBIGUOUS_DENIED;
            if (!(itemMeta instanceof BlockStateMeta meta)) return StorageDecision.AMBIGUOUS_DENIED;
            BlockState state = meta.getBlockState();
            if (state == null) return StorageDecision.AMBIGUOUS_DENIED;
            return classifyKnownStorage(state);
        }
        catch (RuntimeException failure)
        {
            return StorageDecision.AMBIGUOUS_DENIED;
        }
    }

    public static boolean denyPlacement(ItemStack item)
    {
        StorageDecision decision = classifyPlacement(item);
        return decision == StorageDecision.PROTECTED_NONEMPTY
                || decision == StorageDecision.AUTOMATION_DENIED
                || decision == StorageDecision.AMBIGUOUS_DENIED;
    }

    /**
     * Only supported block-state holders with a bounded readable inventory
     * may be copied into a detached preview.
     */
    public static boolean supportsDetachedView(BlockState state)
    {
        if (state == null || state instanceof Campfire) return false;
        if (!(state instanceof InventoryHolder holder)) return false;
        return supportsDetachedInventory(holder);
    }

    public static boolean supportsDetachedInventory(InventoryHolder holder)
    {
        if (holder == null || holder instanceof HumanEntity || holder instanceof Merchant
                || !(holder instanceof Chest || holder instanceof Barrel
                || holder instanceof Furnace || holder instanceof BlastFurnace
                || holder instanceof Smoker || holder instanceof Hopper
                || holder instanceof Dropper || holder instanceof Dispenser
                || holder instanceof BrewingStand || holder instanceof Crafter
                || holder instanceof ShulkerBox || holder instanceof DecoratedPot
                || holder instanceof ChiseledBookshelf || holder instanceof Jukebox
                || holder instanceof Lectern || holder instanceof DoubleChest)) return false;
        try
        {
            Inventory inventory = holder.getInventory();
            return inventory != null && inventory.getSize() <= 54;
        }
        catch (RuntimeException failure)
        {
            return false;
        }
    }

    public static boolean supportsDetachedView(Merchant merchant)
    {
        return false;
    }

    public static boolean supportsDetachedView(HumanEntity human)
    {
        return false;
    }

    private static StorageDecision classifyKnownStorage(BlockState state)
    {
        if (!(state instanceof InventoryHolder holder)) return StorageDecision.AMBIGUOUS_DENIED;
        return classifyInventory(holder);
    }

    private static StorageDecision classifyCampfire(BlockState state)
    {
        if (!(state instanceof Campfire campfire)) return StorageDecision.AMBIGUOUS_DENIED;
        try
        {
            int size = campfire.getSize();
            if (size < 0 || size > 4) return StorageDecision.AMBIGUOUS_DENIED;
            for (int index = 0; index < size; index++)
            {
                if (campfire.getItem(index) != null) return StorageDecision.PROTECTED_NONEMPTY;
            }
            return StorageDecision.EMPTY_BREAKABLE;
        }
        catch (RuntimeException failure)
        {
            return StorageDecision.AMBIGUOUS_DENIED;
        }
    }

    private static boolean isSupportedBlockState(Material material, BlockState state)
    {
        if (material == null || state == null) return false;
        return switch (material)
        {
            case CHEST, TRAPPED_CHEST -> state instanceof Chest;
            case BARREL -> state instanceof Barrel;
            case FURNACE -> state instanceof Furnace;
            case BLAST_FURNACE -> state instanceof BlastFurnace;
            case SMOKER -> state instanceof Smoker;
            case HOPPER -> state instanceof Hopper;
            case DROPPER -> state instanceof Dropper;
            case DISPENSER -> state instanceof Dispenser;
            case BREWING_STAND -> state instanceof BrewingStand;
            case CRAFTER -> state instanceof Crafter;
            case SHULKER_BOX, WHITE_SHULKER_BOX, ORANGE_SHULKER_BOX,
                    MAGENTA_SHULKER_BOX, LIGHT_BLUE_SHULKER_BOX, YELLOW_SHULKER_BOX,
                    LIME_SHULKER_BOX, PINK_SHULKER_BOX, GRAY_SHULKER_BOX,
                    LIGHT_GRAY_SHULKER_BOX, CYAN_SHULKER_BOX, PURPLE_SHULKER_BOX,
                    BLUE_SHULKER_BOX, BROWN_SHULKER_BOX, GREEN_SHULKER_BOX,
                    RED_SHULKER_BOX, BLACK_SHULKER_BOX -> state instanceof ShulkerBox;
            case DECORATED_POT -> state instanceof DecoratedPot;
            case CHISELED_BOOKSHELF -> state instanceof ChiseledBookshelf;
            case JUKEBOX -> state instanceof Jukebox;
            case LECTERN -> state instanceof Lectern;
            case CAMPFIRE, SOUL_CAMPFIRE -> state instanceof Campfire;
            default -> false;
        };
    }

    private static StorageDecision classifyInventory(InventoryHolder holder)
    {
        if (holder == null || holder instanceof HumanEntity || holder instanceof Merchant)
        {
            return StorageDecision.AMBIGUOUS_DENIED;
        }
        try
        {
            Inventory inventory = holder.getInventory();
            if (inventory == null) return StorageDecision.AMBIGUOUS_DENIED;
            return inventory.isEmpty()
                    ? StorageDecision.EMPTY_BREAKABLE : StorageDecision.PROTECTED_NONEMPTY;
        }
        catch (RuntimeException failure)
        {
            return StorageDecision.AMBIGUOUS_DENIED;
        }
    }

    private static boolean isShulker(Material material)
    {
        return material != null
                && (material == Material.SHULKER_BOX || material.name().endsWith("_SHULKER_BOX"));
    }
}
