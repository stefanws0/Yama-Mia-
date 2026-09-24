package com.yamareviewer.domain.projection;

import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.DomainEvent;
import com.yamareviewer.domain.event.FightEnded;
import com.yamareviewer.domain.event.GameStateKind;
import com.yamareviewer.domain.event.GameStateObserved;
import com.yamareviewer.domain.event.GraphicObserved;
import com.yamareviewer.domain.event.NpcDespawnObserved;
import com.yamareviewer.domain.event.NpcSpawnObserved;
import com.yamareviewer.domain.event.OverheadTextObserved;
import com.yamareviewer.domain.event.VarbitObserved;
import com.yamareviewer.domain.ids.IdRegistry;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.model.Phase;
import com.yamareviewer.domain.review.PhaseSpan;
import com.yamareviewer.domain.review.PhaseTimes;
import com.yamareviewer.domain.review.Section;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Spec 6.2 and 5.3. P1 starts at FightStarted. A Judge phase starts at the earliest signal while Yama is
 * alive: a change of PHASE_VARBIT to a new non-zero value, a PHASE_TRANSITION_TEXT line from Yama,
 * PHASE_TRANSITION_GRAPHIC on Yama, or a Judge spawn. It ends when every Judge that spawned in it is gone;
 * a non-dying despawn inside a scene reload (LOADING) is ignored and a respawn inside one adds nothing.
 * The log cannot tell the two Judges of a duo apart (hitsplats carry no NPC index), so the phase ends at
 * the last Judge despawn. P3 ends at FightEnded; a log without FightEnded ends at its last event.
 */
public final class PhasesProjection implements Projection<PhaseTimes>
{
	/** Transition signals this soon after a Judge phase ended belong to the return to the arena, not to a new Judge. */
	static final int TRANSITION_SETTLE_TICKS = 10;

	@Override
	public SectionKey<PhaseTimes> key()
	{
		return Sections.PHASES;
	}

	@Override
	public Set<Role> requiredRoles()
	{
		return EnumSet.of(Role.JUDGE);
	}

	@Override
	public Section<PhaseTimes> project(KillLog log, ProjectionContext context)
	{
		IdRegistry ids = context.ids();
		List<PhaseSpan> spans = new ArrayList<>();
		Set<Integer> liveJudges = new HashSet<>();
		Phase phase = Phase.P1;
		int start = 0;
		int end = log.lastTick();
		boolean loading = false;
		boolean yamaAlive = true;
		boolean judgeSeen = false;
		int lastJudgeEnd = -TRANSITION_SETTLE_TICKS - 1;
		int previousVarbit = 0;

		for (DomainEvent event : log.getEvents())
		{
			int tick = event.getTick();
			if (event instanceof GameStateObserved)
			{
				loading = ((GameStateObserved) event).getState() == GameStateKind.LOADING;
				continue;
			}
			if (event instanceof FightEnded)
			{
				end = tick;
				break;
			}
			if (isYamaDeath(event))
			{
				yamaAlive = false;
				continue;
			}
			boolean varbitChanged = false;
			if (event instanceof VarbitObserved && ids.is(Role.PHASE_VARBIT, ((VarbitObserved) event).getVarbitId()))
			{
				int value = ((VarbitObserved) event).getValue();
				varbitChanged = value != previousVarbit && value != 0;
				previousVarbit = value;
			}

			if (phase.isJudge())
			{
				if (isJudgeSpawn(event, ids))
				{
					liveJudges.add(((NpcSpawnObserved) event).getNpcIndex());
					judgeSeen = true;
				}
				else if (isJudgeDespawn(event, ids) && (!loading || ((NpcDespawnObserved) event).isDying()))
				{
					liveJudges.remove(((NpcDespawnObserved) event).getNpcIndex());
					if (judgeSeen && liveJudges.isEmpty())
					{
						spans.add(new PhaseSpan(phase, start, tick));
						phase = next(phase);
						start = tick;
						lastJudgeEnd = tick;
					}
				}
			}
			else if (phase != Phase.P3 && yamaAlive && tick - lastJudgeEnd > TRANSITION_SETTLE_TICKS
				&& (varbitChanged || isTransitionSignal(event, ids) || isJudgeSpawn(event, ids)))
			{
				spans.add(new PhaseSpan(phase, start, tick));
				phase = next(phase);
				start = tick;
				liveJudges.clear();
				judgeSeen = false;
				if (isJudgeSpawn(event, ids))
				{
					liveJudges.add(((NpcSpawnObserved) event).getNpcIndex());
					judgeSeen = true;
				}
			}
		}
		spans.add(new PhaseSpan(phase, start, end));
		return Section.ok(new PhaseTimes(List.copyOf(spans), end));
	}

	private static Phase next(Phase phase)
	{
		return Phase.values()[phase.ordinal() + 1];
	}

	private static boolean isYamaDeath(DomainEvent event)
	{
		if (!(event instanceof NpcDespawnObserved))
		{
			return false;
		}
		NpcDespawnObserved despawn = (NpcDespawnObserved) event;
		return despawn.getActor().equals(Actor.YAMA) && despawn.isDying();
	}

	private static boolean isJudgeSpawn(DomainEvent event, IdRegistry ids)
	{
		if (!(event instanceof NpcSpawnObserved))
		{
			return false;
		}
		NpcSpawnObserved spawn = (NpcSpawnObserved) event;
		return spawn.getActor().equals(Actor.JUDGE) || ids.is(Role.JUDGE, spawn.getNpcId());
	}

	private static boolean isJudgeDespawn(DomainEvent event, IdRegistry ids)
	{
		if (!(event instanceof NpcDespawnObserved))
		{
			return false;
		}
		NpcDespawnObserved despawn = (NpcDespawnObserved) event;
		return despawn.getActor().equals(Actor.JUDGE) || ids.is(Role.JUDGE, despawn.getNpcId());
	}

	private static boolean isTransitionSignal(DomainEvent event, IdRegistry ids)
	{
		if (event instanceof OverheadTextObserved)
		{
			OverheadTextObserved text = (OverheadTextObserved) event;
			return text.getActor().equals(Actor.YAMA) && ids.matchesText(Role.PHASE_TRANSITION_TEXT, text.getText());
		}
		if (event instanceof GraphicObserved)
		{
			GraphicObserved graphic = (GraphicObserved) event;
			return graphic.getActor().equals(Actor.YAMA) && ids.is(Role.PHASE_TRANSITION_GRAPHIC, graphic.getGraphicId());
		}
		return false;
	}
}
