package com.yamareviewer.domain.projection;

import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.GameMessageObserved;
import com.yamareviewer.domain.event.GraphicObserved;
import com.yamareviewer.domain.event.GroundGraphicObserved;
import com.yamareviewer.domain.event.HitsplatObserved;
import com.yamareviewer.domain.event.Position;
import com.yamareviewer.domain.event.TickState;
import com.yamareviewer.domain.ids.IdRegistry;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.model.Mode;
import com.yamareviewer.domain.review.AttackTimeline;
import com.yamareviewer.domain.review.HiddenReason;
import com.yamareviewer.domain.review.PhaseTimes;
import com.yamareviewer.domain.review.Section;
import com.yamareviewer.domain.review.WaveHit;
import com.yamareviewer.domain.review.WaveSummary;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Shadow Waves (spec 6.7). Every SHADOW_WAVE graphic, on the ground or on a player, belongs to a wave;
 * graphics within WAVE_MERGE_GAP ticks of each other are the same wave. A wave reaches a player at the tick
 * of the wave graphic on them, else at the tick of the ground graphic nearest to them, else at its first tick.
 * The player is hit when a non-standard damage hitsplat lands within crashImpactWindow ticks of that tick.
 */
public final class WavesProjection implements Projection<WaveSummary>
{
	static final int WAVE_MERGE_GAP = 2;

	@Override
	public SectionKey<WaveSummary> key()
	{
		return Sections.WAVES;
	}

	@Override
	public Set<Role> requiredRoles()
	{
		return EnumSet.of(Role.SHADOW_WAVE);
	}

	@Override
	public Section<WaveSummary> project(KillLog log, ProjectionContext context)
	{
		Section<AttackTimeline> attacks = context.section(Sections.ATTACKS);
		Optional<HiddenReason> hidden = Upstream.hidden(attacks);
		if (hidden.isPresent())
		{
			return Section.hidden(hidden.get());
		}
		IdRegistry ids = context.ids();
		Section<PhaseTimes> phases = context.section(Sections.PHASES);
		int window = context.rules().getCrashImpactWindow();
		Set<String> standard = CrashesProjection.standardLandings(attacks.value());
		TreeMap<Integer, TickState> states = CrashesProjection.statesByTick(log);
		List<HitsplatObserved> hitsplats = log.eventsOf(HitsplatObserved.class);
		List<Integer> disabledTicks = new ArrayList<>();
		for (GameMessageObserved message : log.eventsOf(GameMessageObserved.class))
		{
			if (ids.matchesText(Role.PRAYER_DISABLED_MESSAGE, message.getText()))
			{
				disabledTicks.add(message.getTick());
			}
		}
		List<Actor> players = new ArrayList<>();
		players.add(Actor.SELF);
		if (context.value(Sections.MODE).map(mode -> mode != Mode.SOLO).orElse(false))
		{
			players.add(Actor.PARTNER);
		}

		List<WaveHit> hits = new ArrayList<>();
		for (List<WaveGraphic> wave : waves(log, ids))
		{
			for (Actor player : players)
			{
				int reach = reachTick(wave, player, states);
				boolean hit = false;
				int damage = 0;
				for (HitsplatObserved hitsplat : hitsplats)
				{
					if (CrashesProjection.isMechanicDamage(hitsplat, player, standard) && Math.abs(hitsplat.getTick() - reach) <= window)
					{
						hit = true;
						damage += hitsplat.getAmount();
					}
				}
				boolean disabled = false;
				if (Actor.SELF.equals(player))
				{
					for (int tick : disabledTicks)
					{
						if (Math.abs(tick - reach) <= window)
						{
							disabled = true;
						}
					}
				}
				hits.add(new WaveHit(reach, PhaseLookup.phaseAt(phases, reach), player, hit, damage, disabled));
			}
		}
		return Section.ok(new WaveSummary(hits));
	}

	private static List<List<WaveGraphic>> waves(KillLog log, IdRegistry ids)
	{
		TreeMap<Integer, List<WaveGraphic>> byTick = new TreeMap<>();
		for (GroundGraphicObserved graphic : log.eventsOf(GroundGraphicObserved.class))
		{
			if (ids.is(Role.SHADOW_WAVE, graphic.getGraphicId()))
			{
				byTick.computeIfAbsent(graphic.getTick(), tick -> new ArrayList<>()).add(new WaveGraphic(graphic.getTick(), null, graphic.getPosition()));
			}
		}
		for (GraphicObserved graphic : log.eventsOf(GraphicObserved.class))
		{
			if (graphic.getActor().isPlayer() && ids.is(Role.SHADOW_WAVE, graphic.getGraphicId()))
			{
				byTick.computeIfAbsent(graphic.getTick(), tick -> new ArrayList<>()).add(new WaveGraphic(graphic.getTick(), graphic.getActor(), null));
			}
		}
		List<List<WaveGraphic>> waves = new ArrayList<>();
		Integer last = null;
		for (Map.Entry<Integer, List<WaveGraphic>> entry : byTick.entrySet())
		{
			if (last == null || entry.getKey() - last > WAVE_MERGE_GAP)
			{
				waves.add(new ArrayList<>());
			}
			waves.get(waves.size() - 1).addAll(entry.getValue());
			last = entry.getKey();
		}
		return waves;
	}

	private static int reachTick(List<WaveGraphic> wave, Actor player, TreeMap<Integer, TickState> states)
	{
		for (WaveGraphic graphic : wave)
		{
			if (player.equals(graphic.actor))
			{
				return graphic.tick;
			}
		}
		Integer nearestTick = null;
		int nearest = Integer.MAX_VALUE;
		for (WaveGraphic graphic : wave)
		{
			Position at = playerPosition(states, graphic.tick, player);
			if (graphic.position == null || at == null)
			{
				continue;
			}
			int distance = CrashesProjection.distance(graphic.position, at);
			if (distance < nearest)
			{
				nearest = distance;
				nearestTick = graphic.tick;
			}
		}
		return nearestTick != null ? nearestTick : wave.get(0).tick;
	}

	private static Position playerPosition(TreeMap<Integer, TickState> states, int tick, Actor player)
	{
		Map.Entry<Integer, TickState> entry = states.floorEntry(tick);
		if (entry == null)
		{
			return null;
		}
		return Actor.SELF.equals(player) ? entry.getValue().getSelfPosition() : entry.getValue().getPartnerPosition();
	}

	private static final class WaveGraphic
	{
		private final int tick;
		private final Actor actor;
		private final Position position;

		private WaveGraphic(int tick, Actor actor, Position position)
		{
			this.tick = tick;
			this.actor = actor;
			this.position = position;
		}
	}
}
