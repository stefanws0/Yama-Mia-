package com.yamareviewer.domain.review;

import java.util.List;
import java.util.Optional;
import lombok.Value;

/** Every P3 attack with its tick and gap. Shows observed gaps only; never advice (spec 2, rule 4). */
@Value
public class TickLog
{
	List<TickLogEntry> entries;
	/** The p3AttackCycle the gaps are compared with. */
	int cycle;

	public int gaps()
	{
		return Math.max(0, entries.size() - 1);
	}

	public int gapsOnCycle()
	{
		int count = 0;
		for (TickLogEntry entry : entries)
		{
			if (entry.getGap() != null && entry.getGap() == cycle)
			{
				count++;
			}
		}
		return count;
	}

	public Optional<Double> cycleShare()
	{
		return gaps() == 0 ? Optional.empty() : Optional.of((double) gapsOnCycle() / gaps());
	}
}
