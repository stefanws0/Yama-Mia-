package com.yamareviewer.domain.health;

import com.yamareviewer.domain.contract.ContractRules;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.model.Phase;
import com.yamareviewer.domain.projection.ProjectionContext;
import com.yamareviewer.domain.projection.SectionKey;
import com.yamareviewer.domain.projection.Sections;
import com.yamareviewer.domain.review.Attack;
import com.yamareviewer.domain.review.AttackTimeline;
import com.yamareviewer.domain.review.PhaseSpan;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Spec 8: fewer P3 attacks than minAttackCountRatio × P3 ticks ÷ cycle, or fewer than minCycleGapRatio of the
 * gaps between them being multiples of the cycle, means the attack animation or cast graphics are not recognised.
 * The P3 cycle is 7 ticks under every contract (spec 3, 6.3), so Rules.p3AttackCycle is the contract cycle too.
 */
public final class P3AttackCountCheck implements HealthCheck
{
	/** P3 must span at least this many cycles before the count rule can fail. */
	static final int MIN_CYCLES = 4;
	/** At least this many gaps before the gap rule can fail. */
	static final int MIN_GAPS = 3;

	@Override
	public String name()
	{
		return "P3AttackCount";
	}

	@Override
	public Set<Role> reportRoles()
	{
		return Set.of(Role.YAMA_STANDARD_ATTACK, Role.YAMA_CAST_MAGIC, Role.YAMA_CAST_RANGED);
	}

	@Override
	public List<SectionKey<?>> hides()
	{
		return List.of(Sections.PRAYER_REVIEW, Sections.OPENER, Sections.TICK_LOG);
	}

	@Override
	public boolean applies(ContractRules rules)
	{
		return true;
	}

	@Override
	public CheckResult check(KillLog log, ProjectionContext context)
	{
		Optional<PhaseSpan> p3 = context.value(Sections.PHASES).flatMap(phases -> phases.span(Phase.P3));
		if (p3.isEmpty())
		{
			return CheckResult.skipped("no P3 in this kill");
		}
		Optional<AttackTimeline> timeline = context.value(Sections.ATTACKS);
		if (timeline.isEmpty())
		{
			return CheckResult.skipped("attacks not available");
		}
		int cycle = context.rules().getP3AttackCycle();
		int ticks = p3.get().ticks();
		if (ticks < MIN_CYCLES * cycle)
		{
			return CheckResult.skipped(String.format("P3 lasted %d ticks, fewer than %d cycles of %d", ticks, MIN_CYCLES, cycle));
		}
		List<Integer> casts = timeline.get().inPhase(Phase.P3).stream()
			.map(Attack::getCastTick)
			.sorted()
			.collect(Collectors.toList());
		double ratio = context.rules().getMinAttackCountRatio();
		double minimum = ratio * ticks / cycle;
		if (casts.size() < minimum)
		{
			return CheckResult.fail(String.format("%d standard attacks in %d P3 ticks; at least %.1f expected (%.0f%% of %d/%d)",
				casts.size(), ticks, minimum, ratio * 100, ticks, cycle), p3.get().getStartTick());
		}
		int gaps = casts.size() - 1;
		if (gaps >= MIN_GAPS)
		{
			int multiples = 0;
			Integer firstOdd = null;
			for (int i = 1; i < casts.size(); i++)
			{
				int gap = casts.get(i) - casts.get(i - 1);
				if (gap % cycle == 0)
				{
					multiples++;
				}
				else if (firstOdd == null)
				{
					firstOdd = casts.get(i);
				}
			}
			double gapRatio = context.rules().getMinCycleGapRatio();
			if (multiples < gapRatio * gaps)
			{
				return CheckResult.fail(String.format("%d of %d gaps between P3 attacks are multiples of %d; at least %.0f%% expected",
					multiples, gaps, cycle, gapRatio * 100), firstOdd);
			}
		}
		return CheckResult.pass();
	}
}
