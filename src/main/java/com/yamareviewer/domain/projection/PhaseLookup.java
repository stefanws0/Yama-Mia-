package com.yamareviewer.domain.projection;

import com.yamareviewer.domain.model.Phase;
import com.yamareviewer.domain.review.PhaseSpan;
import com.yamareviewer.domain.review.PhaseTimes;
import com.yamareviewer.domain.review.Section;

/** Which phase a tick belongs to. Spans are contiguous: the tick a Judge despawns already belongs to the next phase. */
final class PhaseLookup
{
	private PhaseLookup()
	{
	}

	/** Null when the phases are hidden or the tick is outside every span. */
	static Phase phaseAt(Section<PhaseTimes> phases, int tick)
	{
		return phases.isOk() ? phaseAt(phases.value(), tick) : null;
	}

	static Phase phaseAt(PhaseTimes phases, int tick)
	{
		PhaseSpan current = null;
		for (PhaseSpan span : phases.getSpans())
		{
			if (span.getStartTick() <= tick)
			{
				current = span;
			}
		}
		return current != null && tick <= current.getEndTick() ? current.getPhase() : null;
	}
}
