package com.yamareviewer.domain.review;

import com.yamareviewer.domain.drain.YamaStats;
import com.yamareviewer.domain.model.Phase;
import lombok.Value;

/** One change of the modelled stats: a landed spec's drain, or a stat restore step. */
@Value
public class DrainStep
{
	int tick;
	/** Null when the phases are unknown. */
	Phase phase;
	/** The weapon's display name, or "Stat restore". */
	String cause;
	YamaStats before;
	YamaStats after;
}
