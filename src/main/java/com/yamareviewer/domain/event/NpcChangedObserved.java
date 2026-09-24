package com.yamareviewer.domain.event;

import lombok.Value;

@Value
public class NpcChangedObserved implements DomainEvent
{
	int tick;
	Actor actor;
	int oldId;
	int newId;
}
