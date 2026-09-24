package com.yamareviewer.domain.event;

import lombok.Value;

@Value
public class WidgetTextObserved implements DomainEvent
{
	int tick;
	int componentId;
	String text;
}
