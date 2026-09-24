package com.yamareviewer.domain.event;

import java.util.Set;
import lombok.Value;

/** State at the end of a tick; recorded on GameTick, after every other event of that tick. */
@Value
public class TickState implements DomainEvent
{
	int tick;
	Set<ProtectionPrayer> prayers;
	int hitpoints;
	int prayerPoints;
	/** Percent, 0-100. */
	int specEnergy;
	/** Percent, 0-100. */
	int runEnergy;
	/** -1 when nothing is wielded. */
	int weaponId;
	/** Null when Yama targets nobody. */
	Actor yamaTarget;
	Position selfPosition;
	/** Null in solo or when the partner is not visible. */
	Position partnerPosition;

	public Set<ProtectionPrayer> getPrayers()
	{
		return prayers == null ? Set.of() : prayers;
	}
}
