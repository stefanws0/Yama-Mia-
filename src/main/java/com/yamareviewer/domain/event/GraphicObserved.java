package com.yamareviewer.domain.event;

import lombok.Value;

@Value
public class GraphicObserved implements DomainEvent
{
	int tick;
	Actor actor;
	int graphicId;
}
