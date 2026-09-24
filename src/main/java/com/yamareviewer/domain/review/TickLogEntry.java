package com.yamareviewer.domain.review;

import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.model.Style;
import java.util.List;
import lombok.Value;

@Value
public class TickLogEntry
{
	int tick;
	Style style;
	Actor target;
	/** Ticks since the previous P3 attack; null for the first. */
	Integer gap;
	/** Crash, wave, flare-summon and melee events between the two attacks when the gap is off the cycle. */
	List<String> annotations;
}
