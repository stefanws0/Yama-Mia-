package com.yamareviewer.domain.review;

import java.util.List;
import lombok.Value;

/** One of the last ticks before a death. hitpoints and prayerPoints are -1 when the tick has no state; runEnergy is set under Forfeit Breath only. */
@Value
public class RecapTick
{
	int tick;
	List<RecapHit> hits;
	int hitpoints;
	int prayerPoints;
	Integer runEnergy;
	/** "you", "partner", "other" or "nobody". */
	String yamaTarget;
	List<String> consumed;
}
