package com.yamareviewer.domain.review;

import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.model.Phase;
import com.yamareviewer.domain.model.Style;
import lombok.Value;

/** One standard attack of Yama (spec 6.4). */
@Value
public class Attack
{
	int castTick;
	/** Null when the phases are unknown. */
	Phase phase;
	/** Null when neither a cast nor an impact graphic identified the style. */
	Style style;
	/** The player the attack was aimed at; null when unknown. */
	Actor target;
	/** Tick of the landing hitsplat on the target; null when none was seen. */
	Integer landingTick;
	/** Amount of the landing hitsplat (0 for a blocked hit); null when none was seen. */
	Integer damage;
}
