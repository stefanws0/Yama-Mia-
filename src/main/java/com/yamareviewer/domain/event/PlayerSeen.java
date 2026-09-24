package com.yamareviewer.domain.event;

import lombok.Value;

/** Another player is in the arena (present at fight start, or loaded in later). */
@Value
public class PlayerSeen implements DomainEvent
{
	int tick;
	String name;
}
