package com.yamareviewer.domain.projection;

import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.AnimationObserved;
import com.yamareviewer.domain.event.GraphicObserved;
import com.yamareviewer.domain.event.HitsplatKind;
import com.yamareviewer.domain.event.HitsplatObserved;
import com.yamareviewer.domain.event.TickState;
import com.yamareviewer.domain.ids.IdRegistry;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.model.Style;
import com.yamareviewer.domain.review.Attack;
import com.yamareviewer.domain.review.AttackTimeline;
import com.yamareviewer.domain.review.PhaseTimes;
import com.yamareviewer.domain.review.Section;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Yama's standard attacks in every phase (spec 6.4). Style: the cast graphic on Yama on the cast tick or
 * the tick after, else the impact graphic on a player within IMPACT_WINDOW ticks. Target: the player with
 * the impact graphic, else Yama's target in the cast tick's TickState. Landing: the first hitsplat Yama
 * dealt to the target at the impact tick or the tick after, or within IMPACT_WINDOW ticks after the cast
 * when no impact was seen.
 */
public final class AttacksProjection implements Projection<AttackTimeline>
{
	static final int IMPACT_WINDOW = 3;
	static final int LANDING_AFTER_IMPACT = 1;

	@Override
	public SectionKey<AttackTimeline> key()
	{
		return Sections.ATTACKS;
	}

	@Override
	public Set<Role> requiredRoles()
	{
		return EnumSet.of(Role.YAMA, Role.YAMA_STANDARD_ATTACK);
	}

	@Override
	public Section<AttackTimeline> project(KillLog log, ProjectionContext context)
	{
		IdRegistry ids = context.ids();
		Section<PhaseTimes> phases = context.section(Sections.PHASES);
		List<GraphicObserved> graphics = log.eventsOf(GraphicObserved.class);
		List<HitsplatObserved> hitsplats = log.eventsOf(HitsplatObserved.class);
		Map<Integer, TickState> states = new HashMap<>();
		for (TickState state : log.eventsOf(TickState.class))
		{
			states.put(state.getTick(), state);
		}

		List<Attack> attacks = new ArrayList<>();
		for (AnimationObserved animation : log.eventsOf(AnimationObserved.class))
		{
			if (!Actor.YAMA.equals(animation.getActor()) || !ids.is(Role.YAMA_STANDARD_ATTACK, animation.getAnimationId()))
			{
				continue;
			}
			int cast = animation.getTick();
			GraphicObserved impact = firstImpact(ids, graphics, cast);
			Style style = castStyle(ids, graphics, cast);
			if (style == null && impact != null)
			{
				style = ids.is(Role.IMPACT_MAGIC, impact.getGraphicId()) ? Style.MAGIC : Style.RANGED;
			}
			Actor target = impact != null ? impact.getActor() : yamaTargetAt(states, cast);
			HitsplatObserved landing = landing(hitsplats, target, cast, impact);
			attacks.add(new Attack(cast, PhaseLookup.phaseAt(phases, cast), style, target,
				landing == null ? null : landing.getTick(), landing == null ? null : landing.getAmount()));
		}
		return Section.ok(new AttackTimeline(attacks));
	}

	private static Style castStyle(IdRegistry ids, List<GraphicObserved> graphics, int cast)
	{
		for (GraphicObserved graphic : graphics)
		{
			if (!Actor.YAMA.equals(graphic.getActor()) || graphic.getTick() < cast || graphic.getTick() > cast + 1)
			{
				continue;
			}
			if (ids.is(Role.YAMA_CAST_MAGIC, graphic.getGraphicId()))
			{
				return Style.MAGIC;
			}
			if (ids.is(Role.YAMA_CAST_RANGED, graphic.getGraphicId()))
			{
				return Style.RANGED;
			}
		}
		return null;
	}

	private static GraphicObserved firstImpact(IdRegistry ids, List<GraphicObserved> graphics, int cast)
	{
		for (GraphicObserved graphic : graphics)
		{
			if (graphic.getActor().isPlayer() && graphic.getTick() >= cast && graphic.getTick() <= cast + IMPACT_WINDOW
				&& (ids.is(Role.IMPACT_MAGIC, graphic.getGraphicId()) || ids.is(Role.IMPACT_RANGED, graphic.getGraphicId())))
			{
				return graphic;
			}
		}
		return null;
	}

	private static Actor yamaTargetAt(Map<Integer, TickState> states, int tick)
	{
		TickState state = states.get(tick);
		Actor target = state == null ? null : state.getYamaTarget();
		return target != null && target.isPlayer() ? target : null;
	}

	private static HitsplatObserved landing(List<HitsplatObserved> hitsplats, Actor target, int cast, GraphicObserved impact)
	{
		if (target == null)
		{
			return null;
		}
		int from = impact == null ? cast + 1 : impact.getTick();
		int to = impact == null ? cast + IMPACT_WINDOW : impact.getTick() + LANDING_AFTER_IMPACT;
		for (HitsplatObserved hitsplat : hitsplats)
		{
			if (hitsplat.getTick() >= from && hitsplat.getTick() <= to && target.equals(hitsplat.getTarget()) && !hitsplat.isMine()
				&& (hitsplat.getKind() == HitsplatKind.DAMAGE || hitsplat.getKind() == HitsplatKind.BLOCK))
			{
				return hitsplat;
			}
		}
		return null;
	}
}
