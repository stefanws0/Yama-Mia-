package com.yamareviewer.domain.projection;

import com.yamareviewer.domain.event.EntryChoice;
import com.yamareviewer.domain.event.EntryChosen;
import com.yamareviewer.domain.event.PlayerSeen;
import com.yamareviewer.domain.event.TickState;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.model.Mode;
import com.yamareviewer.domain.model.Phase;
import com.yamareviewer.domain.review.PhaseSpan;
import com.yamareviewer.domain.review.PhaseTimes;
import com.yamareviewer.domain.review.Section;
import java.util.Optional;
import java.util.Set;

/**
 * Spec 5.4, first match wins: the config override; JOIN → duo joiner; TRAVEL → duo host when another player
 * was seen before the first Judge ended, else solo; no entry choice → with a partner, the east player at the
 * first Judge is the joiner (host when positions are unknown), nobody → solo. Always resolves.
 */
public final class ModeProjection implements Projection<Mode>
{
	@Override
	public SectionKey<Mode> key()
	{
		return Sections.MODE;
	}

	@Override
	public Set<Role> requiredRoles()
	{
		return Set.of();
	}

	@Override
	public Section<Mode> project(KillLog log, ProjectionContext context)
	{
		Mode override = context.settings().getModeOverride();
		if (override != null)
		{
			return Section.ok(override);
		}
		Optional<PhaseTimes> phases = context.value(Sections.PHASES);
		Optional<PhaseSpan> firstJudge = phases.flatMap(times -> times.span(Phase.JUDGE_1));
		int partnerDeadline = firstJudge.map(PhaseSpan::getEndTick).orElse(Integer.MAX_VALUE);
		boolean partnerSeen = log.eventsOf(PlayerSeen.class).stream().anyMatch(seen -> seen.getTick() <= partnerDeadline);
		Optional<EntryChoice> entry = log.eventsOf(EntryChosen.class).stream().map(EntryChosen::getChoice).findFirst();

		if (entry.isPresent())
		{
			if (entry.get() == EntryChoice.JOIN)
			{
				return Section.ok(Mode.DUO_JOINER);
			}
			return Section.ok(partnerSeen ? Mode.DUO_HOST : Mode.SOLO);
		}
		if (!partnerSeen)
		{
			return Section.ok(Mode.SOLO);
		}
		return Section.ok(selfIsEastAtTheJudge(log, firstJudge) ? Mode.DUO_JOINER : Mode.DUO_HOST);
	}

	/** The instance creator is placed west at the Judge (spec 3); unknown positions count as west. */
	private static boolean selfIsEastAtTheJudge(KillLog log, Optional<PhaseSpan> firstJudge)
	{
		if (firstJudge.isEmpty())
		{
			return false;
		}
		PhaseSpan judge = firstJudge.get();
		for (TickState state : log.eventsOf(TickState.class))
		{
			if (state.getTick() >= judge.getStartTick() && state.getTick() <= judge.getEndTick()
				&& state.getSelfPosition() != null && state.getPartnerPosition() != null)
			{
				return state.getSelfPosition().getX() > state.getPartnerPosition().getX();
			}
		}
		return false;
	}
}
