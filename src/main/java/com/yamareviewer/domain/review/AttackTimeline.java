package com.yamareviewer.domain.review;

import com.yamareviewer.domain.model.Phase;
import com.yamareviewer.domain.model.Style;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.Value;

/** Every standard attack of the fight in cast order. */
@Value
public class AttackTimeline
{
	List<Attack> attacks;

	public List<Attack> inPhase(Phase phase)
	{
		return attacks.stream().filter(attack -> attack.getPhase() == phase).collect(Collectors.toList());
	}

	/** Alternation is global: the opposite of the previous attack's style, whoever it targeted (spec 6.4). */
	public Optional<Style> expectedStyle(int index)
	{
		if (index <= 0 || index >= attacks.size())
		{
			return Optional.empty();
		}
		Style previous = attacks.get(index - 1).getStyle();
		return previous == null ? Optional.empty() : Optional.of(opposite(previous));
	}

	public static Style opposite(Style style)
	{
		return style == Style.MAGIC ? Style.RANGED : Style.MAGIC;
	}
}
