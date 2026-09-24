package com.yamareviewer.domain.projection;

import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.AnimationObserved;
import com.yamareviewer.domain.event.GraphicObserved;
import com.yamareviewer.domain.event.GroundGraphicObserved;
import com.yamareviewer.domain.ids.IdRegistry;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.model.Phase;
import com.yamareviewer.domain.review.Attack;
import com.yamareviewer.domain.review.AttackTimeline;
import com.yamareviewer.domain.review.HiddenReason;
import com.yamareviewer.domain.review.PhaseTimes;
import com.yamareviewer.domain.review.Section;
import com.yamareviewer.domain.review.TickLog;
import com.yamareviewer.domain.review.TickLogEntry;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Every P3 attack with the gap from the previous one (spec 6.5). A gap that is not a multiple of the
 * P3 attack cycle is annotated with the crash, wave, flare-summon and melee events between the two
 * attacks. It shows what was observed and gives no advice (spec 2, rule 4).
 */
public final class TickLogProjection implements Projection<TickLog>
{
	static final String CRASH_LINE = "crash line";
	static final String WAVE = "wave";
	static final String FLARE_SUMMON = "flare summon";
	static final String MELEE = "melee";

	@Override
	public SectionKey<TickLog> key()
	{
		return Sections.TICK_LOG;
	}

	@Override
	public Set<Role> requiredRoles()
	{
		return EnumSet.of(Role.YAMA, Role.YAMA_STANDARD_ATTACK);
	}

	@Override
	public Section<TickLog> project(KillLog log, ProjectionContext context)
	{
		Section<PhaseTimes> phases = context.section(Sections.PHASES);
		Section<AttackTimeline> attacks = context.section(Sections.ATTACKS);
		Optional<HiddenReason> hidden = Upstream.hidden(phases, attacks);
		if (hidden.isPresent())
		{
			return Section.hidden(hidden.get());
		}
		int cycle = Math.max(1, context.rules().getP3AttackCycle());
		TreeMap<Integer, Set<String>> events = annotationEvents(log, context.ids());

		List<TickLogEntry> entries = new ArrayList<>();
		Integer previous = null;
		for (Attack attack : attacks.value().inPhase(Phase.P3))
		{
			Integer gap = previous == null ? null : attack.getCastTick() - previous;
			List<String> annotations = new ArrayList<>();
			if (gap != null && gap % cycle != 0)
			{
				for (Map.Entry<Integer, Set<String>> entry : events.subMap(previous, false, attack.getCastTick(), false).entrySet())
				{
					for (String label : entry.getValue())
					{
						annotations.add(label + " at " + entry.getKey());
					}
				}
			}
			entries.add(new TickLogEntry(attack.getCastTick(), attack.getStyle(), attack.getTarget(), gap, annotations));
			previous = attack.getCastTick();
		}
		return Section.ok(new TickLog(entries, cycle));
	}

	/** One label per kind per tick, in the order crash line, wave, flare summon, melee. */
	private static TreeMap<Integer, Set<String>> annotationEvents(KillLog log, IdRegistry ids)
	{
		TreeMap<Integer, Set<String>> events = new TreeMap<>();
		for (GroundGraphicObserved graphic : log.eventsOf(GroundGraphicObserved.class))
		{
			if (ids.is(Role.CRASH_FIREBALL, graphic.getGraphicId()))
			{
				add(events, graphic.getTick(), CRASH_LINE);
			}
		}
		for (GroundGraphicObserved graphic : log.eventsOf(GroundGraphicObserved.class))
		{
			if (ids.is(Role.SHADOW_WAVE, graphic.getGraphicId()))
			{
				add(events, graphic.getTick(), WAVE);
			}
		}
		for (GraphicObserved graphic : log.eventsOf(GraphicObserved.class))
		{
			if (graphic.getActor().isPlayer() && ids.is(Role.SHADOW_WAVE, graphic.getGraphicId()))
			{
				add(events, graphic.getTick(), WAVE);
			}
		}
		for (AnimationObserved animation : log.eventsOf(AnimationObserved.class))
		{
			if (!Actor.YAMA.equals(animation.getActor()))
			{
				continue;
			}
			if (ids.is(Role.YAMA_FLARE_SUMMON, animation.getAnimationId()))
			{
				add(events, animation.getTick(), FLARE_SUMMON);
			}
			else if (ids.is(Role.YAMA_MELEE, animation.getAnimationId()))
			{
				add(events, animation.getTick(), MELEE);
			}
		}
		return events;
	}

	private static void add(TreeMap<Integer, Set<String>> events, int tick, String label)
	{
		events.computeIfAbsent(tick, key -> new LinkedHashSet<>()).add(label);
	}
}
