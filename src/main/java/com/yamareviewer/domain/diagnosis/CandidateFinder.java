package com.yamareviewer.domain.diagnosis;

import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.ActorKind;
import com.yamareviewer.domain.event.AnimationObserved;
import com.yamareviewer.domain.event.GraphicObserved;
import com.yamareviewer.domain.event.GroundGraphicObserved;
import com.yamareviewer.domain.event.HitsplatKind;
import com.yamareviewer.domain.event.HitsplatObserved;
import com.yamareviewer.domain.event.NpcSpawnObserved;
import com.yamareviewer.domain.event.OverheadTextObserved;
import com.yamareviewer.domain.health.SpecDrop;
import com.yamareviewer.domain.health.SpecEnergyDrops;
import com.yamareviewer.domain.ids.IdRegistry;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.ids.RoleKind;
import com.yamareviewer.domain.model.KillLog;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import lombok.Value;

/**
 * Lists ids in a kill's log that the registry doesn't know, of the kinds and on the actors of the given roles,
 * ranked by how well they fit each role's pattern (spec 9): a period near 7 ticks and a lead before hitsplats
 * for attack animations and graphics, a hitsplat right after for impact graphics, same-tick groups of three
 * for crash fireballs, the tick of an unmatched spec-energy drop for spec animations. It reads the log and
 * changes nothing. Kinds that the recorder only keeps for ids with a role (varbits, items, widgets, objects,
 * messages) can't have candidates; unknown NPCs are only in the log in capture mode.
 */
public final class CandidateFinder
{
	/** Ticks after an attack animation or cast graphic within which a player's hitsplat supports it. */
	static final int ATTACK_LEAD = 3;
	/** Ticks after an impact graphic within which a hitsplat on the same actor supports it. */
	static final int IMPACT_LEAD = 2;
	private static final Comparator<Candidate> BEST_FIRST = Comparator.comparingDouble(Candidate::getScore).reversed()
		.thenComparing(Comparator.comparingInt(Candidate::getCount).reversed())
		.thenComparingInt(Candidate::getId)
		.thenComparing(Candidate::getText, Comparator.nullsFirst(Comparator.naturalOrder()));

	private enum Pattern
	{
		PERIODIC_ATTACK,
		HITSPLAT_LEAD,
		TRIPLETS,
		SPEC_DROP,
		COUNT
	}

	private CandidateFinder()
	{
	}

	public static List<Candidate> find(KillLog log, IdRegistry ids, Set<Role> roles)
	{
		if (roles.isEmpty())
		{
			return List.of();
		}
		EnumSet<Role> ordered = EnumSet.noneOf(Role.class);
		ordered.addAll(roles);
		Evidence evidence = new Evidence(log, ids);
		List<Candidate> result = new ArrayList<>();
		for (Role role : ordered)
		{
			List<Candidate> ofRole = new ArrayList<>();
			for (Map.Entry<Integer, List<Occurrence>> entry : evidence.unknownIds(role).entrySet())
			{
				ofRole.add(idCandidate(role, entry.getKey(), entry.getValue(), evidence));
			}
			for (Map.Entry<String, List<Occurrence>> entry : evidence.unknownTexts(role).entrySet())
			{
				List<Occurrence> seen = entry.getValue();
				ofRole.add(new Candidate(role, 0, entry.getKey(), seen.get(0).where, seen.size(),
					seen.get(0).tick, seen.get(seen.size() - 1).tick, 0.0, "seen " + seen.size() + " times"));
			}
			ofRole.sort(BEST_FIRST);
			result.addAll(ofRole);
		}
		return List.copyOf(result);
	}

	private static Candidate idCandidate(Role role, int id, List<Occurrence> seen, Evidence evidence)
	{
		Fit fit;
		switch (patternOf(role))
		{
			case PERIODIC_ATTACK:
				fit = periodicAttack(seen, evidence);
				break;
			case HITSPLAT_LEAD:
				fit = hitsplatLead(seen, evidence);
				break;
			case TRIPLETS:
				fit = triplets(seen);
				break;
			case SPEC_DROP:
				fit = specDrop(seen, evidence);
				break;
			default:
				fit = new Fit(0.0, "seen " + seen.size() + " times");
		}
		return new Candidate(role, id, null, seen.get(0).where, seen.size(), seen.get(0).tick,
			seen.get(seen.size() - 1).tick, fit.score, fit.evidence);
	}

	private static Pattern patternOf(Role role)
	{
		switch (role)
		{
			case YAMA_STANDARD_ATTACK:
			case YAMA_CAST_MAGIC:
			case YAMA_CAST_RANGED:
				return Pattern.PERIODIC_ATTACK;
			case IMPACT_MAGIC:
			case IMPACT_RANGED:
			case CRASH_IMPACT:
			case FLARE_HIT:
				return Pattern.HITSPLAT_LEAD;
			case CRASH_FIREBALL:
				return Pattern.TRIPLETS;
			default:
				return SpecEnergyDrops.SPEC_ROLES.contains(role) ? Pattern.SPEC_DROP : Pattern.COUNT;
		}
	}

	/** The actors an animation or graphic of this role appears on. */
	private static Set<ActorKind> actorsOf(Role role)
	{
		switch (role)
		{
			case YAMA_STANDARD_ATTACK:
			case YAMA_MELEE:
			case YAMA_FLARE_SUMMON:
			case SHADOW_STOMP:
			case YAMA_CAST_MAGIC:
			case YAMA_CAST_RANGED:
			case PHASE_TRANSITION_GRAPHIC:
			case FLARE_HEAL:
				return EnumSet.of(ActorKind.YAMA);
			case FLARE_EXPLODE:
			case FLARE_DEATH:
				return EnumSet.of(ActorKind.FLARE);
			case CRASH_FIREBALL:
				return EnumSet.noneOf(ActorKind.class);
			default:
				return EnumSet.of(ActorKind.SELF, ActorKind.PARTNER);
		}
	}

	/** Graphics of this role also appear as ground graphics. */
	private static boolean onTheGround(Role role)
	{
		switch (role)
		{
			case CRASH_FIREBALL:
			case SHADOW_WAVE:
			case FIRE_STREAK:
			case FIRE_ATTACK:
			case METEOR_STRIKE:
			case SHADOW_POOL:
				return true;
			default:
				return false;
		}
	}

	private static Fit periodicAttack(List<Occurrence> seen, Evidence evidence)
	{
		int gaps = 0;
		int periodic = 0;
		for (int i = 1; i < seen.size(); i++)
		{
			int gap = seen.get(i).tick - seen.get(i - 1).tick;
			if (gap > 0)
			{
				gaps++;
				if (gap % 7 == 0 || gap % 8 == 0)
				{
					periodic++;
				}
			}
		}
		int led = 0;
		for (Occurrence occurrence : seen)
		{
			if (evidence.playerHitsplatBetween(occurrence.tick + 1, occurrence.tick + ATTACK_LEAD))
			{
				led++;
			}
		}
		double periodicShare = gaps == 0 ? 0.0 : (double) periodic / gaps;
		double leadShare = (double) led / seen.size();
		return new Fit((periodicShare + leadShare) / 2, String.format(
			"gaps of 7 or 8 ticks: %d of %d, player hitsplat 1-%d ticks after: %d of %d", periodic, gaps, ATTACK_LEAD, led, seen.size()));
	}

	private static Fit hitsplatLead(List<Occurrence> seen, Evidence evidence)
	{
		int led = 0;
		Map<Integer, Integer> leads = new TreeMap<>();
		for (Occurrence occurrence : seen)
		{
			OptionalInt lead = evidence.hitsplatLeadOn(occurrence.where, occurrence.tick, IMPACT_LEAD);
			if (lead.isPresent())
			{
				led++;
				leads.merge(lead.getAsInt(), 1, Integer::sum);
			}
		}
		String typical = leads.entrySet().stream()
			.max(Map.Entry.comparingByValue())
			.map(entry -> entry.getKey() + " ticks")
			.orElse("none");
		return new Fit((double) led / seen.size(), String.format(
			"hitsplat on the same actor within %d ticks: %d of %d (typical lead %s)", IMPACT_LEAD, led, seen.size(), typical));
	}

	private static Fit triplets(List<Occurrence> seen)
	{
		Map<Integer, Integer> perTick = new TreeMap<>();
		for (Occurrence occurrence : seen)
		{
			perTick.merge(occurrence.tick, 1, Integer::sum);
		}
		int triples = 0;
		int inTriples = 0;
		for (int size : perTick.values())
		{
			if (size % 3 == 0)
			{
				triples++;
				inTriples += size;
			}
		}
		return new Fit((double) inTriples / seen.size(), String.format(
			"same-tick groups of three: %d of %d groups", triples, perTick.size()));
	}

	private static Fit specDrop(List<Occurrence> seen, Evidence evidence)
	{
		int at = 0;
		for (Occurrence occurrence : seen)
		{
			if (evidence.unmatchedDropTicks.contains(occurrence.tick) || evidence.unmatchedDropTicks.contains(occurrence.tick + 1))
			{
				at++;
			}
		}
		return new Fit((double) at / seen.size(), String.format("at an unmatched spec-energy drop: %d of %d", at, seen.size()));
	}

	static String where(Actor actor)
	{
		return actor.getKind() == ActorKind.OTHER ? "OTHER" : actor.getKind().name();
	}

	@Value
	private static class Fit
	{
		double score;
		String evidence;
	}

	@Value
	private static class Occurrence
	{
		int tick;
		String where;
	}

	/** The log, indexed once per find(). */
	private static final class Evidence
	{
		private final KillLog log;
		private final IdRegistry ids;
		private final NavigableSet<Integer> playerHitsplatTicks = new TreeSet<>();
		private final Map<String, NavigableSet<Integer>> hitsplatTicksByActor = new HashMap<>();
		private final Set<Integer> unmatchedDropTicks = new HashSet<>();

		private Evidence(KillLog log, IdRegistry ids)
		{
			this.log = log;
			this.ids = ids;
			for (HitsplatObserved hitsplat : log.eventsOf(HitsplatObserved.class))
			{
				if (hitsplat.getKind() != HitsplatKind.DAMAGE && hitsplat.getKind() != HitsplatKind.BLOCK)
				{
					continue;
				}
				if (hitsplat.getTarget().isPlayer())
				{
					playerHitsplatTicks.add(hitsplat.getTick());
				}
				hitsplatTicksByActor.computeIfAbsent(where(hitsplat.getTarget()), key -> new TreeSet<>()).add(hitsplat.getTick());
			}
			for (SpecDrop drop : SpecEnergyDrops.find(log, ids))
			{
				if (drop.unmatchedKnownWeapon())
				{
					unmatchedDropTicks.add(drop.getTick());
				}
			}
		}

		private boolean playerHitsplatBetween(int from, int to)
		{
			Integer next = playerHitsplatTicks.ceiling(from);
			return next != null && next <= to;
		}

		private OptionalInt hitsplatLeadOn(String where, int tick, int window)
		{
			NavigableSet<Integer> ticks = hitsplatTicksByActor.get(where);
			if (ticks == null)
			{
				return OptionalInt.empty();
			}
			Integer next = ticks.ceiling(tick);
			return next != null && next <= tick + window ? OptionalInt.of(next - tick) : OptionalInt.empty();
		}

		/** Unknown ids of the role's kind on the role's actors, each with its occurrences in log order. */
		private Map<Integer, List<Occurrence>> unknownIds(Role role)
		{
			Map<Integer, List<Occurrence>> result = new TreeMap<>();
			switch (role.kind())
			{
				case ANIMATION:
					for (AnimationObserved animation : log.eventsOf(AnimationObserved.class))
					{
						if (actorsOf(role).contains(animation.getActor().getKind()) && unknown(RoleKind.ANIMATION, animation.getAnimationId()))
						{
							add(result, animation.getAnimationId(), animation.getTick(), where(animation.getActor()));
						}
					}
					break;
				case GRAPHIC:
					for (GraphicObserved graphic : log.eventsOf(GraphicObserved.class))
					{
						if (actorsOf(role).contains(graphic.getActor().getKind()) && unknown(RoleKind.GRAPHIC, graphic.getGraphicId()))
						{
							add(result, graphic.getGraphicId(), graphic.getTick(), where(graphic.getActor()));
						}
					}
					if (onTheGround(role))
					{
						for (GroundGraphicObserved graphic : log.eventsOf(GroundGraphicObserved.class))
						{
							if (unknown(RoleKind.GRAPHIC, graphic.getGraphicId()))
							{
								add(result, graphic.getGraphicId(), graphic.getTick(), "ground");
							}
						}
					}
					break;
				case NPC:
					for (NpcSpawnObserved spawn : log.eventsOf(NpcSpawnObserved.class))
					{
						if (unknown(RoleKind.NPC, spawn.getNpcId()))
						{
							add(result, spawn.getNpcId(), spawn.getTick(), where(spawn.getActor()));
						}
					}
					break;
				default:
					break;
			}
			return result;
		}

		/** Overhead texts from Yama that match no overhead role. */
		private Map<String, List<Occurrence>> unknownTexts(Role role)
		{
			Map<String, List<Occurrence>> result = new TreeMap<>();
			if (role.kind() != RoleKind.OVERHEAD)
			{
				return result;
			}
			for (OverheadTextObserved overhead : log.eventsOf(OverheadTextObserved.class))
			{
				if (overhead.getActor().getKind() != ActorKind.YAMA || overhead.getText() == null || knownText(overhead.getText()))
				{
					continue;
				}
				result.computeIfAbsent(overhead.getText(), key -> new ArrayList<>()).add(new Occurrence(overhead.getTick(), where(overhead.getActor())));
			}
			return result;
		}

		private boolean knownText(String text)
		{
			for (Role role : Role.values())
			{
				if (role.kind() == RoleKind.OVERHEAD && ids.matchesText(role, text))
				{
					return true;
				}
			}
			return false;
		}

		private boolean unknown(RoleKind kind, int id)
		{
			return ids.roleOf(kind, id).isEmpty();
		}

		private static void add(Map<Integer, List<Occurrence>> result, int id, int tick, String where)
		{
			result.computeIfAbsent(id, key -> new ArrayList<>()).add(new Occurrence(tick, where));
		}
	}
}
