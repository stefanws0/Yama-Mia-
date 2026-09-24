package com.yamareviewer.domain.event;

import lombok.Value;

@Value
public class InventoryDelta implements DomainEvent
{
	int tick;
	int itemId;
	String name;
	int change;
}
