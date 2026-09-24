package com.yamareviewer.domain.health;

import com.yamareviewer.domain.contract.ContractRules;
import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.model.Phase;
import com.yamareviewer.domain.projection.ProjectionContext;
import com.yamareviewer.domain.projection.SectionKey;
import com.yamareviewer.domain.projection.Sections;
import com.yamareviewer.domain.review.Attack;
import com.yamareviewer.domain.review.AttackTimeline;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Spec 8: P3 attacks must alternate style. Spec 6.4 says alternation is global, so the check passes when the
 * global sequence alternates; it also passes when each target's own sequence alternates, and fails only when
 * neither view reaches minAlternationRatio. Skipped under random attack styles (Shard Acquisition).
 */
public final class AlternationCheck implements HealthCheck
{
	/** At least this many pairs of consecutive attacks with known styles before the check can fail. */
	static final int MIN_PAIRS = 5;

	@Override
	public String name()
	{
		return "Alternation";
	}

	@Override
	public Set<Role> reportRoles()
	{
		return Set.of(Role.YAMA_CAST_MAGIC, Role.YAMA_CAST_RANGED, Role.IMPACT_MAGIC, Role.IMPACT_RANGED);
	}

	@Override
	public List<SectionKey<?>> hides()
	{
		return List.of(Sections.PRAYER_REVIEW, Sections.OPENER);
	}

	@Override
	public boolean applies(ContractRules rules)
	{
		return !rules.isRandomAttackStyles();
	}

	@Override
	public CheckResult check(KillLog log, ProjectionContext context)
	{
		Optional<AttackTimeline> timeline = context.value(Sections.ATTACKS);
		if (timeline.isEmpty())
		{
			return CheckResult.skipped("attacks not available");
		}
		List<Attack> p3 = timeline.get().inPhase(Phase.P3).stream()
			.sorted(Comparator.comparingInt(Attack::getCastTick))
			.collect(Collectors.toList());
		Pairs global = pairs(p3);
		if (global.total < MIN_PAIRS)
		{
			return CheckResult.skipped(String.format("only %d pairs of consecutive P3 attacks with a known style; %d needed", global.total, MIN_PAIRS));
		}
		Pairs perTarget = new Pairs();
		for (Actor target : List.of(Actor.SELF, Actor.PARTNER))
		{
			perTarget.add(pairs(p3.stream().filter(attack -> target.equals(attack.getTarget())).collect(Collectors.toList())));
		}
		double minimum = context.rules().getMinAlternationRatio();
		boolean globalOk = global.ratio() >= minimum;
		boolean perTargetOk = perTarget.total > 0 && perTarget.ratio() >= minimum;
		if (globalOk || perTargetOk)
		{
			return CheckResult.pass();
		}
		return CheckResult.fail(String.format(
			"consecutive P3 attacks alternate style in %d of %d pairs (%.0f%%) over the whole sequence and %d of %d per target; at least %.0f%% expected",
			global.alternating, global.total, global.ratio() * 100, perTarget.alternating, perTarget.total, minimum * 100), global.firstMismatch);
	}

	/** Pairs of adjacent attacks whose styles are both known. */
	private static Pairs pairs(List<Attack> attacks)
	{
		Pairs pairs = new Pairs();
		for (int i = 1; i < attacks.size(); i++)
		{
			Attack previous = attacks.get(i - 1);
			Attack current = attacks.get(i);
			if (previous.getStyle() == null || current.getStyle() == null)
			{
				continue;
			}
			pairs.total++;
			if (previous.getStyle() != current.getStyle())
			{
				pairs.alternating++;
			}
			else if (pairs.firstMismatch == null)
			{
				pairs.firstMismatch = current.getCastTick();
			}
		}
		return pairs;
	}

	private static final class Pairs
	{
		private int total;
		private int alternating;
		private Integer firstMismatch;

		private double ratio()
		{
			return total == 0 ? 1.0 : (double) alternating / total;
		}

		private void add(Pairs other)
		{
			total += other.total;
			alternating += other.alternating;
			if (firstMismatch == null)
			{
				firstMismatch = other.firstMismatch;
			}
		}
	}
}
