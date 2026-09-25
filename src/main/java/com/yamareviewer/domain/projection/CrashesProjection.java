package com.yamareviewer.domain.projection;

import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.GraphicObserved;
import com.yamareviewer.domain.event.GroundGraphicObserved;
import com.yamareviewer.domain.event.HitsplatKind;
import com.yamareviewer.domain.event.HitsplatObserved;
import com.yamareviewer.domain.event.Position;
import com.yamareviewer.domain.event.TickState;
import com.yamareviewer.domain.ids.IdRegistry;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.review.Attack;
import com.yamareviewer.domain.review.AttackTimeline;
import com.yamareviewer.domain.review.CrashLine;
import com.yamareviewer.domain.review.CrashSummary;
import com.yamareviewer.domain.review.HiddenReason;
import com.yamareviewer.domain.review.PhaseTimes;
import com.yamareviewer.domain.review.Section;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Shadow Crash lines (spec 6.6). CRASH_FIREBALL ground graphics on one tick form lines (fireballs within
 * LINE_SPACING tiles of each other); the centre fireball is the one closest to the others; the line belongs
 * to the player nearest its centre. Lines within crashSetGap ticks of each other form a set. A line is hit
 * when CRASH_IMPACT appears on its player or a non-standard damage hitsplat lands on them within
 * crashImpactWindow ticks of the fireballs' tick.
 */
public final class CrashesProjection implements Projection<CrashSummary>
{
	static final int LINE_SPACING = 3;

	@Override
	public SectionKey<CrashSummary> key()
	{
		return Sections.CRASHES;
	}

	@Override
	public Set<Role> requiredRoles()
	{
		return EnumSet.of(Role.CRASH_FIREBALL);
	}

	@Override
	public Section<CrashSummary> project(KillLog log, ProjectionContext context)
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
		int setGap = context.rules().getCrashSetGap();
		Set<String> standard = standardLandings(attacks.value());
		TreeMap<Integer, TickState> states = statesByTick(log);
		List<GraphicObserved> graphics = log.eventsOf(GraphicObserved.class);
		List<HitsplatObserved> hitsplats = log.eventsOf(HitsplatObserved.class);

		TreeMap<Integer, List<Position>> fireballs = new TreeMap<>();
		for (GroundGraphicObserved graphic : log.eventsOf(GroundGraphicObserved.class))
		{
			if (ids.is(Role.CRASH_FIREBALL, graphic.getGraphicId()) && graphic.getPosition() != null)
			{
				fireballs.computeIfAbsent(graphic.getTick(), tick -> new ArrayList<>()).add(graphic.getPosition());
			}
		}

		List<CrashLine> lines = new ArrayList<>();
		Map<String, Integer> indexInSet = new HashMap<>();
		int set = 0;
		Integer previousTick = null;
		for (Map.Entry<Integer, List<Position>> entry : fireballs.entrySet())
		{
			int tick = entry.getKey();
			if (previousTick == null || tick - previousTick > setGap)
			{
				set++;
			}
			previousTick = tick;
			for (List<Position> line : cluster(entry.getValue()))
			{
				Position centre = centreOf(line);
				Actor player = nearestPlayer(states, tick, centre);
				int index = indexInSet.merge(set + "/" + player.getKind(), 1, Integer::sum);
				boolean impact = false;
				for (GraphicObserved graphic : graphics)
				{
					if (player.equals(graphic.getActor()) && ids.is(Role.CRASH_IMPACT, graphic.getGraphicId())
						&& Math.abs(graphic.getTick() - tick) <= window)
					{
						impact = true;
					}
				}
				int damage = 0;
				boolean hitsplatHit = false;
				for (HitsplatObserved hitsplat : hitsplats)
				{
					if (isMechanicDamage(hitsplat, player, standard) && Math.abs(hitsplat.getTick() - tick) <= window)
					{
						hitsplatHit = true;
						damage += hitsplat.getAmount();
					}
				}
				lines.add(new CrashLine(tick, PhaseLookup.phaseAt(phases, tick), player, set, index, centre, impact || hitsplatHit, damage));
			}
		}
		return Section.ok(new CrashSummary(lines));
	}

	/** "tick/actor kind" of every standard attack's landing hitsplat, so it is never counted as a mechanic hit. */
	static Set<String> standardLandings(AttackTimeline timeline)
	{
		Set<String> keys = new HashSet<>();
		for (Attack attack : timeline.getAttacks())
		{
			if (attack.getLandingTick() != null && attack.getTarget() != null)
			{
				keys.add(attack.getLandingTick() + "/" + attack.getTarget().getKind());
			}
		}
		return keys;
	}

	/**
	 * A damage hitsplat Yama's mechanics dealt to the player, other than a standard attack's landing. isMine() is not
	 * consulted: a hit the local player receives is one of the game's *_ME types, so it is "mine" too.
	 */
	static boolean isMechanicDamage(HitsplatObserved hitsplat, Actor player, Set<String> standardLandings)
	{
		return player.equals(hitsplat.getTarget()) && hitsplat.getKind() == HitsplatKind.DAMAGE
			&& !standardLandings.contains(hitsplat.getTick() + "/" + player.getKind());
	}

	static TreeMap<Integer, TickState> statesByTick(KillLog log)
	{
		TreeMap<Integer, TickState> states = new TreeMap<>();
		for (TickState state : log.eventsOf(TickState.class))
		{
			states.put(state.getTick(), state);
		}
		return states;
	}

	static int distance(Position a, Position b)
	{
		return Math.max(Math.abs(a.getX() - b.getX()), Math.abs(a.getY() - b.getY()));
	}

	/** The player nearest the centre, from the TickState of the tick (or the latest before it); you in solo. */
	static Actor nearestPlayer(TreeMap<Integer, TickState> states, int tick, Position centre)
	{
		Map.Entry<Integer, TickState> entry = states.floorEntry(tick);
		if (entry == null || entry.getValue().getPartnerPosition() == null || entry.getValue().getSelfPosition() == null)
		{
			return Actor.SELF;
		}
		int self = distance(centre, entry.getValue().getSelfPosition());
		int partner = distance(centre, entry.getValue().getPartnerPosition());
		return partner < self ? Actor.PARTNER : Actor.SELF;
	}

	private static List<List<Position>> cluster(List<Position> positions)
	{
		List<List<Position>> clusters = new ArrayList<>();
		for (Position position : positions)
		{
			List<Position> home = null;
			for (Iterator<List<Position>> it = clusters.iterator(); it.hasNext(); )
			{
				List<Position> cluster = it.next();
				boolean near = false;
				for (Position member : cluster)
				{
					if (distance(position, member) <= LINE_SPACING)
					{
						near = true;
						break;
					}
				}
				if (!near)
				{
					continue;
				}
				if (home == null)
				{
					cluster.add(position);
					home = cluster;
				}
				else
				{
					home.addAll(cluster);
					it.remove();
				}
			}
			if (home == null)
			{
				List<Position> cluster = new ArrayList<>();
				cluster.add(position);
				clusters.add(cluster);
			}
		}
		return clusters;
	}

	/** The fireball with the smallest total distance to the others; the first on a tie. */
	private static Position centreOf(List<Position> line)
	{
		Position centre = line.get(0);
		int best = Integer.MAX_VALUE;
		for (Position candidate : line)
		{
			int total = 0;
			for (Position other : line)
			{
				total += distance(candidate, other);
			}
			if (total < best)
			{
				best = total;
				centre = candidate;
			}
		}
		return centre;
	}
}
