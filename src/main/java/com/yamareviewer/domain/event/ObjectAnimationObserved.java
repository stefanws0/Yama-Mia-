package com.yamareviewer.domain.event;

import lombok.Value;

@Value
public class ObjectAnimationObserved implements DomainEvent
{
	int tick;
	int objectId;
	Position position;
	int animationId;
}
