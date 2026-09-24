package com.yamareviewer.domain.review;

import com.yamareviewer.domain.event.Actor;
import lombok.Value;

/** A soulflame horn blown, and the melee spec within 10 ticks it assisted (spec 6.9). */
@Value
public class HornUse
{
	int tick;
	Actor user;
	/** The first melee spec by anyone within 10 ticks; null when the horn was unused. */
	SpecResult assistedSpec;

	public boolean isUnused()
	{
		return assistedSpec == null;
	}
}
