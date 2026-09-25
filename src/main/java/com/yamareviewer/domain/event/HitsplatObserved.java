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
	/**
	 * RuneLite's Hitsplat.isMine(): true for the game's *_ME types, which mark every hitsplat involving the local
	 * player, dealt or received. It means "dealt by you" only on a hitsplat on an NPC.
	 */
	boolean mine;
}
