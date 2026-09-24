package com.yamareviewer.domain.event;

import lombok.Value;

@Value
public class NpcSpawnObserved implements DomainEvent
{
	int tick;
	Actor actor;
	int npcId;
	int npcIndex;
}
