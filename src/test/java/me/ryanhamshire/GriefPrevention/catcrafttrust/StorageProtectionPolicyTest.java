package me.ryanhamshire.GriefPrevention.catcrafttrust;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Campfire;
import org.bukkit.block.Barrel;
import org.bukkit.block.BlastFurnace;
import org.bukkit.block.BrewingStand;
import org.bukkit.block.Chest;
import org.bukkit.block.ChiseledBookshelf;
import org.bukkit.block.Container;
import org.bukkit.block.DecoratedPot;
import org.bukkit.block.Furnace;
import org.bukkit.block.Jukebox;
import org.bukkit.block.Lectern;
import org.bukkit.block.ShulkerBox;
import org.bukkit.block.Smoker;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.JukeboxInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ChiseledBookshelfInventory;
import org.bukkit.inventory.DecoratedPotInventory;
import org.bukkit.inventory.Merchant;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyInt;

class StorageProtectionPolicyTest
{
    private static final List<Material> CONTAINER_MATERIALS = List.of(
            Material.CHEST, Material.TRAPPED_CHEST, Material.BARREL,
            Material.FURNACE, Material.BLAST_FURNACE, Material.SMOKER,
            Material.SHULKER_BOX, Material.WHITE_SHULKER_BOX, Material.BLACK_SHULKER_BOX,
            Material.BREWING_STAND);

    @Test
    void supportedContainerFamiliesClassifyEmptyAndNonEmptyContents()
    {
        for (Material material : CONTAINER_MATERIALS)
        {
            org.bukkit.inventory.InventoryHolder state = supportedState(material);
            Inventory inventory = state.getInventory();
            when(inventory.isEmpty()).thenReturn(true, false);

            assertEquals(StorageProtectionPolicy.StorageDecision.EMPTY_BREAKABLE,
                    StorageProtectionPolicy.classifyBreak(block(material, (BlockState) state)), material.name());
            assertEquals(StorageProtectionPolicy.StorageDecision.PROTECTED_NONEMPTY,
                    StorageProtectionPolicy.classifyBreak(block(material, (BlockState) state)), material.name());
        }
    }

    @Test
    void automationMaterialsAreDeniedEvenWhenEmpty()
    {
        for (Material material : List.of(Material.HOPPER, Material.DROPPER, Material.DISPENSER,
                Material.CRAFTER))
        {
            Chest state = mock(Chest.class);
            Inventory inventory = mock(Inventory.class);
            when(state.getInventory()).thenReturn(inventory);
            when(inventory.isEmpty()).thenReturn(true);

            assertEquals(StorageProtectionPolicy.StorageDecision.AUTOMATION_DENIED,
                    StorageProtectionPolicy.classifyBreak(block(material, state)), material.name());
            assertTrue(StorageProtectionPolicy.denyPlacement(material), material.name());
        }
    }

    @Test
    void specialStorageBlocksUseTheirDetachedInventoryState()
    {
        DecoratedPot pot = mock(DecoratedPot.class);
        DecoratedPotInventory potInventory = mock(DecoratedPotInventory.class);
        when(pot.getInventory()).thenReturn(potInventory);
        when(potInventory.isEmpty()).thenReturn(true);
        assertEquals(StorageProtectionPolicy.StorageDecision.EMPTY_BREAKABLE,
                StorageProtectionPolicy.classifyBreak(block(Material.DECORATED_POT, pot)));

        ChiseledBookshelf bookshelf = mock(ChiseledBookshelf.class);
        ChiseledBookshelfInventory bookshelfInventory = mock(ChiseledBookshelfInventory.class);
        when(bookshelf.getInventory()).thenReturn(bookshelfInventory);
        when(bookshelfInventory.isEmpty()).thenReturn(false);
        assertEquals(StorageProtectionPolicy.StorageDecision.PROTECTED_NONEMPTY,
                StorageProtectionPolicy.classifyBreak(block(Material.CHISELED_BOOKSHELF, bookshelf)));
    }

    @Test
    void ordinaryBlocksAreBreakableButUnknownStorageFailsClosed()
    {
        assertEquals(StorageProtectionPolicy.StorageDecision.ORDINARY,
                StorageProtectionPolicy.classifyBreak(block(Material.STONE, mock(BlockState.class))));

        Container unreadable = mock(Container.class);
        doThrow(new IllegalStateException("unreadable")).when(unreadable).getInventory();
        assertEquals(StorageProtectionPolicy.StorageDecision.AMBIGUOUS_DENIED,
                StorageProtectionPolicy.classifyBreak(block(Material.CHEST, unreadable)));

        assertEquals(StorageProtectionPolicy.StorageDecision.AMBIGUOUS_DENIED,
                StorageProtectionPolicy.classifyBreak(block(Material.CHEST, mock(BlockState.class))));
        assertEquals(StorageProtectionPolicy.StorageDecision.AMBIGUOUS_DENIED,
                StorageProtectionPolicy.classifyBreak(null));
    }

    @Test
    void detachedViewExcludesHumanAndMerchantAndUnknownHolders()
    {
        assertFalse(StorageProtectionPolicy.supportsDetachedView(mock(HumanEntity.class)));
        assertFalse(StorageProtectionPolicy.supportsDetachedView((Merchant) mock(Merchant.class)));
        assertFalse(StorageProtectionPolicy.supportsDetachedView((BlockState) mock(BlockState.class)));

        Chest state = mock(Chest.class);
        Inventory inventory = mock(Inventory.class);
        when(state.getInventory()).thenReturn(inventory);
        when(inventory.getSize()).thenReturn(27);
        assertTrue(StorageProtectionPolicy.supportsDetachedView(state));
        assertFalse(StorageProtectionPolicy.supportsDetachedInventory(mock(org.bukkit.inventory.InventoryHolder.class)));
    }

    @Test
    void campfireContentsAreProtectedConservatively()
    {
        Campfire campfire = mock(Campfire.class);
        when(campfire.getSize()).thenReturn(4);
        when(campfire.getItem(anyInt())).thenReturn(null);
        assertEquals(StorageProtectionPolicy.StorageDecision.EMPTY_BREAKABLE,
                StorageProtectionPolicy.classifyBreak(block(Material.CAMPFIRE, campfire)));

        when(campfire.getItem(0)).thenReturn(new ItemStack(Material.STONE));
        assertEquals(StorageProtectionPolicy.StorageDecision.PROTECTED_NONEMPTY,
                StorageProtectionPolicy.classifyBreak(block(Material.CAMPFIRE, campfire)));
    }

    @Test
    void unreadableCampfireAndSpecialStorageFailClosed()
    {
        Campfire campfire = mock(Campfire.class);
        when(campfire.getSize()).thenThrow(new IllegalStateException("unreadable"));
        assertEquals(StorageProtectionPolicy.StorageDecision.AMBIGUOUS_DENIED,
                StorageProtectionPolicy.classifyBreak(block(Material.SOUL_CAMPFIRE, campfire)));

        Jukebox jukebox = mock(Jukebox.class);
        JukeboxInventory jukeboxInventory = mock(JukeboxInventory.class);
        when(jukebox.getInventory()).thenReturn(jukeboxInventory);
        when(jukeboxInventory.isEmpty()).thenReturn(false);
        assertEquals(StorageProtectionPolicy.StorageDecision.PROTECTED_NONEMPTY,
                StorageProtectionPolicy.classifyBreak(block(Material.JUKEBOX, jukebox)));

        Lectern lectern = mock(Lectern.class);
        Inventory lecternInventory = mock(Inventory.class);
        when(lectern.getInventory()).thenReturn(lecternInventory);
        when(lecternInventory.isEmpty()).thenReturn(false);
        assertEquals(StorageProtectionPolicy.StorageDecision.PROTECTED_NONEMPTY,
                StorageProtectionPolicy.classifyBreak(block(Material.LECTERN, lectern)));
    }

    @Test
    void shulkerItemWithUnreadableOrNonEmptyBlockStateFailsClosed()
    {
        ItemStack item = mock(ItemStack.class);
        BlockStateMeta meta = mock(BlockStateMeta.class);
        Container state = mock(Container.class);
        Inventory inventory = mock(Inventory.class);
        when(item.getType()).thenReturn(Material.SHULKER_BOX);
        when(item.getItemMeta()).thenReturn(meta);
        when(meta.getBlockState()).thenReturn(state);
        when(state.getType()).thenReturn(Material.SHULKER_BOX);
        when(state.getInventory()).thenReturn(inventory);
        when(inventory.isEmpty()).thenReturn(false);

        assertEquals(StorageProtectionPolicy.StorageDecision.PROTECTED_NONEMPTY,
                StorageProtectionPolicy.classifyPlacement(item));
        when(meta.getBlockState()).thenThrow(new IllegalStateException("unreadable"));
        assertEquals(StorageProtectionPolicy.StorageDecision.AMBIGUOUS_DENIED,
                StorageProtectionPolicy.classifyPlacement(item));

        when(item.getItemMeta()).thenReturn(null);
        assertEquals(StorageProtectionPolicy.StorageDecision.AMBIGUOUS_DENIED,
                StorageProtectionPolicy.classifyPlacement(item));
    }

    @Test
    void ordinaryAndStoragePlacementDecisionsAreExplicit()
    {
        assertFalse(StorageProtectionPolicy.denyPlacement(Material.STONE));
        assertTrue(StorageProtectionPolicy.denyPlacement(Material.HOPPER));
        assertTrue(StorageProtectionPolicy.denyPlacement(Material.DROPPER));
        assertTrue(StorageProtectionPolicy.denyPlacement(Material.DISPENSER));
        assertTrue(StorageProtectionPolicy.denyPlacement(Material.CRAFTER));
    }

    private static Block block(Material material, BlockState state)
    {
        Block block = mock(Block.class);
        when(block.getType()).thenReturn(material);
        when(block.getState()).thenReturn(state);
        return block;
    }

    private static org.bukkit.inventory.InventoryHolder supportedState(Material material)
    {
        return switch (material)
        {
            case CHEST, TRAPPED_CHEST -> mock(Chest.class, RETURNS_DEEP_STUBS);
            case BARREL -> mock(Barrel.class, RETURNS_DEEP_STUBS);
            case FURNACE -> mock(Furnace.class, RETURNS_DEEP_STUBS);
            case BLAST_FURNACE -> mock(BlastFurnace.class, RETURNS_DEEP_STUBS);
            case SMOKER -> mock(Smoker.class, RETURNS_DEEP_STUBS);
            case SHULKER_BOX, WHITE_SHULKER_BOX, BLACK_SHULKER_BOX -> mock(ShulkerBox.class, RETURNS_DEEP_STUBS);
            case BREWING_STAND -> mock(BrewingStand.class, RETURNS_DEEP_STUBS);
            default -> throw new IllegalArgumentException(material.name());
        };
    }
}
