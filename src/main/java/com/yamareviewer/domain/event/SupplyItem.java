package com.yamareviewer.domain.event;

import lombok.Value;

/** One item line of a supplies snapshot, priced when the snapshot was taken. */
@Value
public class SupplyItem
{
	int itemId;
	String name;
	int quantity;
	int gePrice;
	int haPrice;
}
