package com.yamareviewer.domain.health;

import com.yamareviewer.domain.contract.ContractRules;
import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.projection.ProjectionContext;
import com.yamareviewer.domain.projection.SectionKey;
import com.yamareviewer.domain.projection.Sections;
import com.yamareviewer.domain.review.CrashLine;
import com.yamareviewer.domain.review.CrashSummary;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Spec 8: crash lines are grouped into sets by time across players (a new set starts after a gap longer than
 * crashSetGap); every set except the last must have exactly three lines for each player that has lines in it.
 * The last set may be cut short by the end of the fight. No lines at all is no evidence.
 */
public final class CrashSetsCheck implements HealthCheck
{
	static final int LINES_PER_PLAYER = 3;

	@Override
	public String name()
	{
		return "CrashSets";
	}

	@Override
	public Set<Role> reportRoles()
	{
		return Set.of(Role.CRASH_FIREBALL, Role.CRASH_IMPACT);
	}

	@Override
	public List<SectionKey<?>> hides()
	{
		return List.of(Sections.CRASHES);
	}

	@Override
	public boolean applies(ContractRules rules)
	{
		return true;
	}

	@Override
	public CheckResult check(KillLog log, ProjectionContext context)
	{
		Optional<CrashSummary> crashes = context.value(Sections.CRASHES);
		if (crashes.isEmpty())
		{
			return CheckResult.skipped("crash lines not available");
		}
		List<CrashLine> lines = crashes.get().getLines().stream()
			.sorted(Comparator.comparingInt(CrashLine::getTick))
			.collect(Collectors.toList());
		if (lines.isEmpty())
		{
			return CheckResult.pass();
		}
		List<List<CrashLine>> sets = sets(lines, context.rules().getCrashSetGap());
		for (int i = 0; i < sets.size() - 1; i++)
		{
			List<CrashLine> set = sets.get(i);
			Map<Actor, Integer> perPlayer = new LinkedHashMap<>();
			for (CrashLine line : set)
			{
				perPlayer.merge(line.getPlayer(), 1, Integer::sum);
			}
			for (Map.Entry<Actor, Integer> entry : perPlayer.entrySet())
			{
				if (entry.getValue() != LINES_PER_PLAYER)
				{
					int first = set.get(0).getTick();
					int last = set.get(set.size() - 1).getTick();
					return CheckResult.fail(String.format(
						"crash set %d of %d (ticks %d-%d) has %d lines for %s; %d per player expected (this contract expects %d sets in P3)",
						i + 1, sets.size(), first, last, entry.getValue(), entry.getKey().getKind(), LINES_PER_PLAYER,
						context.contractRules().getP3CrashSets()), first);
				}
			}
		}
		return CheckResult.pass();
	}

	private static List<List<CrashLine>> sets(List<CrashLine> sorted, int gap)
	{
		List<List<CrashLine>> sets = new ArrayList<>();
		List<CrashLine> current = new ArrayList<>();
		for (CrashLine line : sorted)
		{
			if (!current.isEmpty() && line.getTick() - current.get(current.size() - 1).getTick() > gap)
			{
				sets.add(current);
				current = new ArrayList<>();
			}
			current.add(line);
		}
		sets.add(current);
		return sets;
	}
}
