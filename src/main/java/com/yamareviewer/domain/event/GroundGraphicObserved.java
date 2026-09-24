package com.yamareviewer.domain.event;

import lombok.Value;

@Value
public class GroundGraphicObserved implements DomainEvent
{
	int tick;
	int graphicId;
	Position position;
}
