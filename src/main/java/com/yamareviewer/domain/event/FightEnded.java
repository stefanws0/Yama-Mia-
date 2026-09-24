package com.yamareviewer.domain.event;

import lombok.Value;

@Value
public class FightEnded implements DomainEvent
{
	int tick;
	EndReason reason;
}
