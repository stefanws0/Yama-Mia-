package com.yamareviewer.domain.event;

import lombok.Value;

@Value
public class FightStarted implements DomainEvent
{
	int tick;
	String selfName;
	Position selfPosition;
}
