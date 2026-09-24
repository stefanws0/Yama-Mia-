package com.yamareviewer.domain.health;

import com.yamareviewer.domain.contract.ContractRules;
import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.model.Phase;
import com.yamareviewer.domain.projection.ProjectionContext;
import com.yamareviewer.domain.projection.SectionKey;
import com.yamareviewer.domain.projection.Sections;
import com.yamareviewer.domain.review.PhaseSpan;
import com.yamareviewer.domain.review.PhaseTimes;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Spec 8: a kill that ended with Yama's death must have P1, J1, P2, J2 and P3 in that order; any other kill
 * must have a prefix of that order.
 */
public final class PhaseOrderCheck implements HealthCheck
{
	static final List<Phase> ORDER = List.of(Phase.P1, Phase.JUDGE_1, Phase.P2, Phase.JUDGE_2, Phase.P3);

	@Override
	public String name()
	{
		return "PhaseOrder";
	}

	@Override
	public Set<Role> reportRoles()
	{
		return Set.of(Role.JUDGE, Role.PHASE_VARBIT, Role.PHASE_TRANSITION_TEXT, Role.PHASE_TRANSITION_GRAPHIC);
	}

	@Override
	public List<SectionKey<?>> hides()
	{
		return List.of(Sections.PHASES, Sections.DAMAGE, Sections.PRAYER_REVIEW, Sections.OPENER, Sections.TICK_LOG);
	}

	@Override
	public boolean applies(ContractRules rules)
	{
		return true;
	}

	@Override
	public CheckResult check(KillLog log, ProjectionContext context)
	{
		Optional<PhaseTimes> phases = context.value(Sections.PHASES);
		if (phases.isEmpty())
		{
			return CheckResult.skipped("phases not available");
		}
		List<PhaseSpan> spans = phases.get().getSpans();
		List<Phase> reached = spans.stream().map(PhaseSpan::getPhase).collect(Collectors.toList());
		boolean complete = log.endReason() == EndReason.YAMA_DIED;
		boolean prefix = reached.size() <= ORDER.size() && ORDER.subList(0, reached.size()).equals(reached);
		if (prefix && (!complete || reached.size() == ORDER.size()))
		{
			return CheckResult.pass();
		}
		return CheckResult.fail(String.format("phases seen: %s; expected %s%s", names(reached), names(ORDER),
			complete ? " for a kill that ended with Yama's death" : " or a prefix of it"), firstDeviation(spans, prefix));
	}

	/** The start of the first span out of order, or the end of the last span when a phase is missing after it. */
	private static int firstDeviation(List<PhaseSpan> spans, boolean prefix)
	{
		if (prefix)
		{
			return spans.isEmpty() ? 0 : spans.get(spans.size() - 1).getEndTick();
		}
		for (int i = 0; i < spans.size(); i++)
		{
			if (i >= ORDER.size() || spans.get(i).getPhase() != ORDER.get(i))
			{
				return spans.get(i).getStartTick();
			}
		}
		return 0;
	}

	private static String names(List<Phase> phases)
	{
		return phases.isEmpty() ? "none" : phases.stream().map(Phase::name).collect(Collectors.joining(", "));
	}
}
