package com.yamareviewer.domain.event;

import lombok.Value;

@Value
public class ObjectSpawnObserved implements DomainEvent
{
	int tick;
	int objectId;
	Position position;
}
