package com.yamareviewer.domain.review;

import com.yamareviewer.domain.model.Phase;
import java.util.List;
import java.util.Optional;
import lombok.Value;

/** The phases reached, in order; totalTicks is the tick the fight ended on. */
@Value
public class PhaseTimes
{
	List<PhaseSpan> spans;
	int totalTicks;

	public Optional<PhaseSpan> span(Phase phase)
	{
		return spans.stream().filter(span -> span.getPhase() == phase).findFirst();
	}

	public Optional<PhaseSpan> last()
	{
		return spans.isEmpty() ? Optional.empty() : Optional.of(spans.get(spans.size() - 1));
	}

	/** Spans are half-open, except the last one, which includes its end tick (the fight end). */
	public Optional<Phase> phaseAt(int tick)
	{
		for (int i = 0; i < spans.size(); i++)
		{
			PhaseSpan span = spans.get(i);
			boolean last = i == spans.size() - 1;
			if (tick >= span.getStartTick() && (tick < span.getEndTick() || (last && tick == span.getEndTick())))
			{
				return Optional.of(span.getPhase());
			}
		}
		return Optional.empty();
	}
}
