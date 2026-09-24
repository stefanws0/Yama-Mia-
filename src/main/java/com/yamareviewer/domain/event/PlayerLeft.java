package com.yamareviewer.domain.event;

import lombok.Value;

@Value
public class PlayerLeft implements DomainEvent
{
	int tick;
	String name;
}
