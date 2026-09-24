package com.yamareviewer.domain.event;

import lombok.Value;

@Value
public class AnimationObserved implements DomainEvent
{
	int tick;
	Actor actor;
	int animationId;
}
