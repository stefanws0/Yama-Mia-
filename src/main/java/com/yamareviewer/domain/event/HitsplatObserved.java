package com.yamareviewer.domain.event;

import lombok.Value;

@Value
public class HitsplatObserved implements DomainEvent
{
	int tick;
	Actor target;
	HitsplatKind kind;
	int amount;
	int rawType;
	/** True when the local player dealt this hitsplat (RuneLite's Hitsplat.isMine()). */
	boolean mine;
}
