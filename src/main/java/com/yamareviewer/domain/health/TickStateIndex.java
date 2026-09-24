package com.yamareviewer.domain.health;

import com.yamareviewer.domain.event.ProtectionPrayer;
import com.yamareviewer.domain.event.TickState;
import com.yamareviewer.domain.model.KillLog;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** The TickState of each tick of a kill (the last one recorded for a tick wins). */
public final class TickStateIndex
{
	private final Map<Integer, TickState> byTick;

	private TickStateIndex(Map<Integer, TickState> byTick)
	{
		this.byTick = byTick;
	}

	public static TickStateIndex of(KillLog log)
	{
		Map<Integer, TickState> byTick = new HashMap<>();
		for (TickState state : log.eventsOf(TickState.class))
		{
			byTick.put(state.getTick(), state);
		}
		return new TickStateIndex(byTick);
	}

	public Optional<TickState> at(int tick)
	{
		return Optional.ofNullable(byTick.get(tick));
	}

	/** SELF's protection prayers at the end of the tick; none when the tick has no state. */
	public Set<ProtectionPrayer> prayersAt(int tick)
	{
		return at(tick).map(TickState::getPrayers).orElse(Set.of());
	}
}
