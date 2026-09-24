package com.yamareviewer.adapter.recording;

import net.runelite.client.game.ItemManager;

/** ItemManager reads item definitions, which requires the client thread. */
public final class ItemManagerLookup implements ItemLookup
{
	private final ItemManager itemManager;

	public ItemManagerLookup(ItemManager itemManager)
	{
		this.itemManager = itemManager;
	}

	@Override
	public String name(int itemId)
	{
		return itemManager.getItemComposition(itemId).getName();
	}

	@Override
	public int gePrice(int itemId)
	{
		return itemManager.getItemPrice(itemId);
	}

	@Override
	public int haPrice(int itemId)
	{
		return itemManager.getItemComposition(itemId).getHaPrice();
	}
}
