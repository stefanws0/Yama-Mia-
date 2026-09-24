package com.yamareviewer.adapter.recording;

import com.yamareviewer.domain.event.SnapshotKind;
import com.yamareviewer.domain.event.SuppliesSnapshot;
import com.yamareviewer.domain.event.SupplyItem;
import java.util.List;
import java.util.Optional;
import net.runelite.api.Client;
import net.runelite.api.EnumComposition;
import net.runelite.api.EnumID;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.VarbitID;
import static org.junit.Assert.assertEquals;
import org.junit.Before;
import org.junit.Test;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class SnapshotReaderTest
{
	private final Client client = mock(Client.class);
	private final ItemLookup items = new ItemLookup()
	{
		@Override
		public String name(int itemId)
		{
			return "Item " + itemId;
		}

		@Override
		public int gePrice(int itemId)
		{
			return itemId * 10;
		}

		@Override
		public int haPrice(int itemId)
		{
			return itemId;
		}
	};
	private final SnapshotReader reader = new SnapshotReader(client, items);

	@Before
	public void setUp()
	{
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		ItemContainer inventory = mock(ItemContainer.class);
		when(inventory.getItems()).thenReturn(new Item[]{new Item(385, 1), new Item(385, 1), new Item(-1, 0), new Item(556, 10)});
		when(client.getItemContainer(InventoryID.INV)).thenReturn(inventory);
		EnumComposition runes = mock(EnumComposition.class);
		when(runes.getIntValue(1)).thenReturn(556);
		when(client.getEnum(EnumID.RUNEPOUCH_RUNE)).thenReturn(runes);
		when(client.getVarbitValue(VarbitID.RUNE_POUCH_TYPE_6)).thenReturn(1);
		when(client.getVarbitValue(VarbitID.RUNE_POUCH_QUANTITY_6)).thenReturn(1000);
	}

	@Test
	public void aggregatesInventoryAndAllSixRunePouchSlotsWithPrices()
	{
		Optional<SuppliesSnapshot> snapshot = reader.take(4, SnapshotKind.START);

		assertEquals(Optional.of(new SuppliesSnapshot(4, SnapshotKind.START, List.of(
			new SupplyItem(385, "Item 385", 2, 3850, 385),
			new SupplyItem(556, "Item 556", 1010, 5560, 556)))), snapshot);
	}

	@Test
	public void notLoggedInGivesNoSnapshot()
	{
		when(client.getGameState()).thenReturn(GameState.LOGIN_SCREEN);

		assertEquals(Optional.empty(), reader.take(4, SnapshotKind.END));
	}
}
