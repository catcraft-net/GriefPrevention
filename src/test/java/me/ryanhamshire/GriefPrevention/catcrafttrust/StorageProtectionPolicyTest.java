package me.ryanhamshire.GriefPrevention.catcrafttrust;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.ChiseledBookshelf;
import org.bukkit.block.Container;
import org.bukkit.block.DecoratedPot;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
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
import static org.mockito.Mockito.when;

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
            Container state = mock(Container.class);
            Inventory inventory = mock(Inventory.class);
            when(state.getInventory()).thenReturn(inventory);
            when(inventory.isEmpty()).thenReturn(true, false);

            assertEquals(StorageProtectionPolicy.StorageDecision.EMPTY_BREAKABLE,
                    StorageProtectionPolicy.classifyBreak(block(material, state)), material.name());
            assertEquals(StorageProtectionPolicy.StorageDecision.PROTECTED_NONEMPTY,
                    StorageProtectionPolicy.classifyBreak(block(material, state)), material.name());
        }
    }

    @Test
    void automationMaterialsAreDeniedEvenWhenEmpty()
    {
        for (Material material : List.of(Material.HOPPER, Material.DROPPER, Material.DISPENSER,
                Material.CRAFTER))
        {
            Container state = mock(Container.class);
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

        Container state = mock(Container.class);
        Inventory inventory = mock(Inventory.class);
        when(state.getInventory()).thenReturn(inventory);
        assertTrue(StorageProtectionPolicy.supportsDetachedView(state));
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
}
