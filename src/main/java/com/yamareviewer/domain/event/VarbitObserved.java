package com.yamareviewer.domain.event;

import lombok.Value;

@Value
public class VarbitObserved implements DomainEvent
{
	int tick;
	int varbitId;
	int value;
}
