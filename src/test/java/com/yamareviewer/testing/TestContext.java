package com.yamareviewer.testing;

import com.yamareviewer.domain.ids.Rules;
import com.yamareviewer.domain.model.Contract;
import com.yamareviewer.domain.model.Mode;
import com.yamareviewer.domain.projection.ProjectionContext;
import com.yamareviewer.domain.projection.ReviewSettings;
import com.yamareviewer.domain.projection.Sections;
import com.yamareviewer.domain.review.Section;

/** ProjectionContexts with the upstream sections a projection under test needs, put by hand. */
public final class TestContext
{
	private TestContext()
	{
	}

	public static ProjectionContext empty()
	{
		return withRules(Rules.DEFAULT);
	}

	public static ProjectionContext withRules(Rules rules)
	{
		return new ProjectionContext(TestIds.registry(), rules, ReviewSettings.DEFAULT);
	}

	/** Solo, no contract, the Fights skeleton with P3 ending at endTick. */
	public static ProjectionContext withPhases(int endTick)
	{
		return withPhases(Rules.DEFAULT, endTick);
	}

	public static ProjectionContext withPhases(Rules rules, int endTick)
	{
		ProjectionContext context = withRules(rules);
		context.put(Sections.PHASES, Section.ok(Fights.phases(endTick)));
		context.put(Sections.CONTRACT, Section.ok(Contract.NONE));
		context.put(Sections.MODE, Section.ok(Mode.SOLO));
		return context;
	}

	public static ProjectionContext duo(int endTick)
	{
		ProjectionContext context = withPhases(endTick);
		context.put(Sections.MODE, Section.ok(Mode.DUO_HOST));
		return context;
	}

	public static ProjectionContext under(Contract contract, int endTick)
	{
		ProjectionContext context = withPhases(endTick);
		context.put(Sections.CONTRACT, Section.ok(contract));
		return context;
	}
}
