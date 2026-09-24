package com.yamareviewer.domain.event;

import lombok.Value;

/** A system message (ChatMessageType.GAMEMESSAGE), colour tags kept: they carry meaning (shadow or fire). */
@Value
public class GameMessageObserved implements DomainEvent
{
	int tick;
	String text;
}
