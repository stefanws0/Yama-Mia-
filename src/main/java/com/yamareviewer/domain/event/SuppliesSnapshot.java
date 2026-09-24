package com.yamareviewer.domain.event;

import java.util.List;
import lombok.Value;

@Value
public class SuppliesSnapshot implements DomainEvent
{
	int tick;
	SnapshotKind kind;
	/** Inventory plus rune pouch contents, aggregated per item id. */
	List<SupplyItem> items;

	public List<SupplyItem> getItems()
	{
		return items == null ? List.of() : items;
	}
}
