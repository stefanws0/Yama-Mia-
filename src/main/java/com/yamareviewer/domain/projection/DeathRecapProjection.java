package com.yamareviewer.domain.projection;

import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.event.FightEnded;
import com.yamareviewer.domain.event.HitsplatKind;
import com.yamareviewer.domain.event.HitsplatObserved;
import com.yamareviewer.domain.event.InventoryDelta;
import com.yamareviewer.domain.event.TickState;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.review.DamageHit;
import com.yamareviewer.domain.review.DamageSource;
import com.yamareviewer.domain.review.DamageSummary;
import com.yamareviewer.domain.review.DeathRecap;
import com.yamareviewer.domain.review.HiddenReason;
import com.yamareviewer.domain.review.RecapHit;
import com.yamareviewer.domain.review.RecapTick;
import com.yamareviewer.domain.review.Section;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Spec 6.12: for a PLAYER_DIED kill, the 10 ticks up to and including the death tick (the FightEnded tick).
 * Hit sources come from the damage section; when it is hidden every hit is OTHER. Run energy is shown only
 * under Forfeit Breath, and a death with 0% run energy there is "out of run energy".
 */
public final class DeathRecapProjection implements Projection<DeathRecap>
{
	static final int RECAP_TICKS = 10;

	@Override
	public SectionKey<DeathRecap> key()
	{
		return Sections.DEATH_RECAP;
	}

	@Override
	public Set<Role> requiredRoles()
	{
		return Set.of();
	}

	@Override
	public Section<DeathRecap> project(KillLog log, ProjectionContext context)
	{
		if (log.endReason() != EndReason.PLAYER_DIED)
		{
			return Section.hidden(HiddenReason.NOT_APPLICABLE);
		}
		List<FightEnded> ended = log.eventsOf(FightEnded.class);
		int deathTick = ended.get(ended.size() - 1).getTick();
		int firstTick = Math.max(0, deathTick - RECAP_TICKS + 1);
		boolean showRunEnergy = context.contractRules().isRunEnergyDeath();

		Map<Integer, TickState> states = new HashMap<>();
		log.eventsOf(TickState.class).forEach(state -> states.put(state.getTick(), state));
		Map<Integer, List<RecapHit>> hits = hitsByTick(log, context.value(Sections.DAMAGE));
		Map<Integer, List<String>> consumed = new HashMap<>();
		for (InventoryDelta delta : log.eventsOf(InventoryDelta.class))
		{
			if (delta.getChange() < 0)
			{
				consumed.computeIfAbsent(delta.getTick(), tick -> new ArrayList<>()).add(delta.getName());
			}
		}

		List<RecapTick> ticks = new ArrayList<>();
		for (int tick = firstTick; tick <= deathTick; tick++)
		{
			TickState state = states.get(tick);
			ticks.add(new RecapTick(
				tick,
				List.copyOf(hits.getOrDefault(tick, List.of())),
				state == null ? -1 : state.getHitpoints(),
				state == null ? -1 : state.getPrayerPoints(),
				showRunEnergy && state != null ? state.getRunEnergy() : null,
				targetLabel(state),
				List.copyOf(consumed.getOrDefault(tick, List.of()))));
		}
		TickState death = states.get(deathTick);
		boolean outOfRunEnergy = showRunEnergy && death != null && death.getRunEnergy() == 0;
		return Section.ok(new DeathRecap(List.copyOf(ticks), outOfRunEnergy));
	}

	private static Map<Integer, List<RecapHit>> hitsByTick(KillLog log, Optional<DamageSummary> damage)
	{
		Map<Integer, List<RecapHit>> hits = new HashMap<>();
		if (damage.isPresent())
		{
			for (DamageHit hit : damage.get().getHits())
			{
				if (hit.getPlayer().equals(Actor.SELF))
				{
					hits.computeIfAbsent(hit.getTick(), tick -> new ArrayList<>()).add(new RecapHit(hit.getSource(), hit.getAmount()));
				}
			}
			return hits;
		}
		for (HitsplatObserved hitsplat : log.eventsOf(HitsplatObserved.class))
		{
			if (hitsplat.getTarget().equals(Actor.SELF) && hitsplat.getKind() == HitsplatKind.DAMAGE)
			{
				hits.computeIfAbsent(hitsplat.getTick(), tick -> new ArrayList<>()).add(new RecapHit(DamageSource.OTHER, hitsplat.getAmount()));
			}
		}
		return hits;
	}

	private static String targetLabel(TickState state)
	{
		if (state == null || state.getYamaTarget() == null)
		{
			return "nobody";
		}
		if (state.getYamaTarget().equals(Actor.SELF))
		{
			return "you";
		}
		return state.getYamaTarget().equals(Actor.PARTNER) ? "partner" : "other";
	}
}
