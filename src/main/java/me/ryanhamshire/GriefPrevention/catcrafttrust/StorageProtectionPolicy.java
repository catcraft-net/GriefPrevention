package me.ryanhamshire.GriefPrevention.catcrafttrust;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.BlockStateMeta;
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
            Material.CHISELED_BOOKSHELF);

    private static final Set<Material> AUTOMATION_MATERIALS = EnumSet.of(
            Material.HOPPER, Material.DROPPER, Material.DISPENSER, Material.CRAFTER,
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
        if (!STORAGE_MATERIALS.contains(material))
        {
            return state instanceof InventoryHolder
                    ? StorageDecision.AMBIGUOUS_DENIED : StorageDecision.ORDINARY;
        }
        return classifyKnownStorage(state);
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
            if (itemMeta == null) return StorageDecision.EMPTY_BREAKABLE;
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
        if (!(state instanceof InventoryHolder holder)) return false;
        return supportsDetachedInventory(holder);
    }

    public static boolean supportsDetachedInventory(InventoryHolder holder)
    {
        if (holder == null || holder instanceof HumanEntity || holder instanceof Merchant) return false;
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
