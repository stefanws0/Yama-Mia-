package com.yamareviewer.domain.review;

import com.yamareviewer.domain.model.Phase;
import lombok.Value;

/** One phase: from its start tick (inclusive) to the next phase's start (exclusive), or the fight end. */
@Value
public class PhaseSpan
{
	Phase phase;
	int startTick;
	int endTick;

	public int ticks()
	{
		return endTick - startTick;
	}

	public double seconds()
	{
		return ticks() * 0.6;
	}
}
