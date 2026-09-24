package com.yamareviewer.domain.event;

import lombok.Value;

@Value
public class OverheadTextObserved implements DomainEvent
{
	int tick;
	Actor actor;
	String text;
}
