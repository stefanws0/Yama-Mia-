package com.yamareviewer.adapter.recording;

import com.yamareviewer.application.port.SnapshotSource;
import com.yamareviewer.domain.event.SnapshotKind;
import com.yamareviewer.domain.event.SuppliesSnapshot;
import com.yamareviewer.domain.event.SupplyItem;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import net.runelite.api.Client;
import net.runelite.api.EnumComposition;
import net.runelite.api.EnumID;
import net.runelite.api.GameState;
import net.runelite.api.ItemContainer;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.VarbitID;

/** Inventory plus the six rune pouch slots, named and priced on the client thread. */
public final class SnapshotReader implements SnapshotSource
{
	private static final int[] RUNE_TYPES = {
		VarbitID.RUNE_POUCH_TYPE_1, VarbitID.RUNE_POUCH_TYPE_2, VarbitID.RUNE_POUCH_TYPE_3,
		VarbitID.RUNE_POUCH_TYPE_4, VarbitID.RUNE_POUCH_TYPE_5, VarbitID.RUNE_POUCH_TYPE_6
	};
	private static final int[] RUNE_QUANTITIES = {
		VarbitID.RUNE_POUCH_QUANTITY_1, VarbitID.RUNE_POUCH_QUANTITY_2, VarbitID.RUNE_POUCH_QUANTITY_3,
		VarbitID.RUNE_POUCH_QUANTITY_4, VarbitID.RUNE_POUCH_QUANTITY_5, VarbitID.RUNE_POUCH_QUANTITY_6
	};

	private final Client client;
	private final ItemLookup lookup;

	public SnapshotReader(Client client, ItemLookup lookup)
	{
		this.client = client;
		this.lookup = lookup;
	}

	@Override
	public Optional<SuppliesSnapshot> take(int tick, SnapshotKind kind)
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return Optional.empty();
		}
		ItemContainer inventory = client.getItemContainer(InventoryID.INV);
		if (inventory == null)
		{
			return Optional.empty();
		}
		Map<Integer, Integer> quantities = new TreeMap<>(EventTranslator.aggregate(inventory.getItems()));
		EnumComposition runeItems = client.getEnum(EnumID.RUNEPOUCH_RUNE);
		for (int i = 0; i < RUNE_TYPES.length; i++)
		{
			int type = client.getVarbitValue(RUNE_TYPES[i]);
			int quantity = client.getVarbitValue(RUNE_QUANTITIES[i]);
			if (type > 0 && quantity > 0)
			{
				quantities.merge(runeItems.getIntValue(type), quantity, Integer::sum);
			}
		}
		List<SupplyItem> items = new ArrayList<>();
		quantities.forEach((itemId, quantity) -> items.add(
			new SupplyItem(itemId, lookup.name(itemId), quantity, lookup.gePrice(itemId), lookup.haPrice(itemId))));
		return Optional.of(new SuppliesSnapshot(tick, kind, List.copyOf(items)));
	}
}
