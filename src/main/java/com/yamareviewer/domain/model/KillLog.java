package com.yamareviewer.domain.model;

import com.yamareviewer.domain.event.DomainEvent;
import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.event.FightEnded;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Value;

/** The frozen, append-only event stream of one kill: the write model. */
@Value
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class KillLog
{
	public static final int SCHEMA_VERSION = 1;

	KillHeader header;
	List<DomainEvent> events;
	/** Events of an unknown type that could not be read back. */
	int skippedEvents;

	public static KillLog of(KillHeader header, List<? extends DomainEvent> events, int skippedEvents)
	{
		return new KillLog(header, List.copyOf(events), skippedEvents);
	}

	public <T extends DomainEvent> List<T> eventsOf(Class<T> type)
	{
		List<T> result = new ArrayList<>();
		for (DomainEvent event : events)
		{
			if (type.isInstance(event))
			{
				result.add(type.cast(event));
			}
		}
		return result;
	}

	public EndReason endReason()
	{
		List<FightEnded> ended = eventsOf(FightEnded.class);
		return ended.isEmpty() ? EndReason.LEFT : ended.get(ended.size() - 1).getReason();
	}

	public int lastTick()
	{
		return events.isEmpty() ? 0 : events.get(events.size() - 1).getTick();
	}
}
