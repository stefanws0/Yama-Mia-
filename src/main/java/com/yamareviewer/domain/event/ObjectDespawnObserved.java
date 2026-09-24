package com.yamareviewer.domain.event;

import lombok.Value;

@Value
public class ObjectDespawnObserved implements DomainEvent
{
	int tick;
	int objectId;
	Position position;
}
