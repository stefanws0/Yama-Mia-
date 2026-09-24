package com.yamareviewer.domain.event;

import lombok.Value;

@Value
public class EntryChosen implements DomainEvent
{
	int tick;
	EntryChoice choice;
}
