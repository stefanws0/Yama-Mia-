package com.yamareviewer.domain.review;

import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.model.Phase;
import lombok.Value;

/** One attributed damage hitsplat. player is Actor.SELF or Actor.PARTNER; phase is null when the phases are unknown. */
@Value
public class DamageHit
{
	int tick;
	Phase phase;
	Actor player;
	DamageSource source;
	int amount;
}
