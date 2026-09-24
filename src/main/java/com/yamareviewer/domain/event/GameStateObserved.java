package com.yamareviewer.domain.event;

import lombok.Value;

@Value
public class GameStateObserved implements DomainEvent
{
	int tick;
	GameStateKind state;
}
