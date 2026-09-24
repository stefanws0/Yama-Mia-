package com.yamareviewer.domain.projection;

import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.ActorKind;
import com.yamareviewer.domain.event.AnimationObserved;
import com.yamareviewer.domain.event.GraphicObserved;
import com.yamareviewer.domain.event.GroundGraphicObserved;
import com.yamareviewer.domain.event.HitsplatKind;
import com.yamareviewer.domain.event.HitsplatObserved;
import com.yamareviewer.domain.event.NpcDespawnObserved;
import com.yamareviewer.domain.event.NpcSpawnObserved;
import com.yamareviewer.domain.event.Position;
import com.yamareviewer.domain.event.TickState;
import com.yamareviewer.domain.ids.IdRegistry;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.model.Phase;
import com.yamareviewer.domain.review.DamageHit;
import com.yamareviewer.domain.review.DamageSource;
import com.yamareviewer.domain.review.DamageSummary;
import com.yamareviewer.domain.review.HiddenReason;
import com.yamareviewer.domain.review.PhaseTimes;
import com.yamareviewer.domain.review.Section;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Spec 6.10: every damage hitsplat on SELF or PARTNER gets exactly one source, first matching rule wins.
 * Part 2 applies rules 2 and 5–10; Part 3 adds rule 1 (STANDARD, from Sections.ATTACKS) and rules 3 and 4
 * (SHADOW_CRASH from Sections.CRASHES, SHADOW_WAVE from Sections.WAVES) in {@link #rules}.
 * Under a shadow-pool contract the section is hidden when SHADOW_POOL is uncaptured, so pool damage is
 * never shown as OTHER.
 */
public final class DamageAttributionProjection implements Projection<DamageSummary>
{
	static final int MELEE_WINDOW_TICKS = 2;
	static final int FLARE_WINDOW_TICKS = 1;
	static final int STREAK_WINDOW_TICKS = 2;
	static final int SPECIAL_WINDOW_TICKS = 2;
	static final int POOL_WINDOW_TICKS = 1;

	private static final Set<Role> REQUIRED = EnumSet.of(Role.YAMA_MELEE, Role.FLARE_HIT, Role.FLARE_EXPLODE, Role.FIRE_STREAK,
		Role.METEOR_STRIKE, Role.SHADOW_STOMP, Role.FIRE_ATTACK, Role.GLYPH_PROTECTION);

	/** One rule of spec 6.10; the first rule that answers decides. */
	interface SourceRule
	{
		Optional<DamageSource> apply(HitsplatObserved hit);
	}

	@Override
	public SectionKey<DamageSummary> key()
	{
		return Sections.DAMAGE;
	}

	@Override
	public Set<Role> requiredRoles()
	{
		return EnumSet.copyOf(REQUIRED);
	}

	@Override
	public Section<DamageSummary> project(KillLog log, ProjectionContext context)
	{
		if (context.contractRules().isShadowPools() && !context.ids().isCaptured(Role.SHADOW_POOL))
		{
			return Section.hidden(HiddenReason.IDS_NOT_CAPTURED);
		}
		Optional<PhaseTimes> phases = context.value(Sections.PHASES);
		List<SourceRule> rules = rules(log, context);
		List<DamageHit> hits = new ArrayList<>();
		for (HitsplatObserved hit : log.eventsOf(HitsplatObserved.class))
		{
			if (hit.getKind() != HitsplatKind.DAMAGE || !hit.getTarget().isPlayer())
			{
				continue;
			}
			DamageSource source = DamageSource.OTHER;
			for (SourceRule rule : rules)
			{
				Optional<DamageSource> answer = rule.apply(hit);
				if (answer.isPresent())
				{
					source = answer.get();
					break;
				}
			}
			Phase phase = phases.flatMap(times -> times.phaseAt(hit.getTick())).orElse(null);
			hits.add(new DamageHit(hit.getTick(), phase, hit.getTarget(), source, hit.getAmount()));
		}
		return Section.ok(new DamageSummary(List.copyOf(hits)));
	}

	/**
	 * The rules in spec order. Part 3 inserts rule 1 (STANDARD) at the front and rules 3 and 4
	 * (SHADOW_CRASH, SHADOW_WAVE) right after the melee rule.
	 */
	List<SourceRule> rules(KillLog log, ProjectionContext context)
	{
		IdRegistry ids = context.ids();
		Map<Integer, TickState> states = new HashMap<>();
		log.eventsOf(TickState.class).forEach(state -> states.put(state.getTick(), state));
		List<Integer> meleeTicks = yamaAnimationTicks(log, ids, Role.YAMA_MELEE);
		Map<Actor, List<Integer>> flareHitTicks = playerGraphicTicks(log, ids, Role.FLARE_HIT);
		List<Integer> explodeTicks = flareAnimationTicks(log, ids, Role.FLARE_EXPLODE);
		List<Integer> streakTicks = graphicTicks(log, ids, Role.FIRE_STREAK);
		List<Integer> specialTicks = new ArrayList<>();
		specialTicks.addAll(graphicTicks(log, ids, Role.METEOR_STRIKE));
		specialTicks.addAll(graphicTicks(log, ids, Role.FIRE_ATTACK));
		specialTicks.addAll(graphicTicks(log, ids, Role.GLYPH_PROTECTION));
		specialTicks.addAll(yamaAnimationTicks(log, ids, Role.SHADOW_STOMP));
		specialTicks.addAll(meteorNpcTicks(log, ids));
		List<GroundGraphicObserved> pools = context.contractRules().isShadowPools()
			? log.eventsOf(GroundGraphicObserved.class).stream().filter(graphic -> ids.is(Role.SHADOW_POOL, graphic.getGraphicId())).collect(Collectors.toList())
			: List.of();
		Optional<PhaseTimes> phases = context.value(Sections.PHASES);

		List<SourceRule> rules = new ArrayList<>();
		P3DamageSources p3 = new P3DamageSources(context);
		rules.add(p3::standard);
		rules.add(hit -> lastAtOrBefore(meleeTicks, hit.getTick(), MELEE_WINDOW_TICKS).map(melee ->
		{
			TickState state = states.get(melee);
			Actor target = state == null ? null : state.getYamaTarget();
			boolean splash = target != null && target.isPlayer() && !target.equals(hit.getTarget());
			return splash ? DamageSource.MELEE_SPLASH : DamageSource.MELEE;
		}));
		rules.add(p3::crashOrWave);
		rules.add(hit -> within(flareHitTicks.getOrDefault(hit.getTarget(), List.of()), hit.getTick(), FLARE_WINDOW_TICKS)
			|| within(explodeTicks, hit.getTick(), FLARE_WINDOW_TICKS)
			? Optional.of(DamageSource.FLARE) : Optional.<DamageSource>empty());
		rules.add(hit -> within(streakTicks, hit.getTick(), STREAK_WINDOW_TICKS)
			? Optional.of(DamageSource.FIRE_STREAK) : Optional.<DamageSource>empty());
		rules.add(hit -> within(specialTicks, hit.getTick(), SPECIAL_WINDOW_TICKS)
			? Optional.of(DamageSource.SPECIAL) : Optional.<DamageSource>empty());
		rules.add(hit -> onAPool(hit, pools, states) ? Optional.of(DamageSource.SHADOW_POOL) : Optional.<DamageSource>empty());
		rules.add(hit -> phases.flatMap(times -> times.phaseAt(hit.getTick())).filter(Phase::isJudge).map(phase -> DamageSource.JUDGE));
		return rules;
	}

	private static List<Integer> yamaAnimationTicks(KillLog log, IdRegistry ids, Role role)
	{
		return log.eventsOf(AnimationObserved.class).stream()
			.filter(animation -> animation.getActor().equals(Actor.YAMA) && ids.is(role, animation.getAnimationId()))
			.map(AnimationObserved::getTick)
			.collect(Collectors.toList());
	}

	private static List<Integer> flareAnimationTicks(KillLog log, IdRegistry ids, Role role)
	{
		return log.eventsOf(AnimationObserved.class).stream()
			.filter(animation -> animation.getActor().getKind() == ActorKind.FLARE && ids.is(role, animation.getAnimationId()))
			.map(AnimationObserved::getTick)
			.collect(Collectors.toList());
	}

	/** Ticks of the role's graphics on any actor plus its ground graphics. */
	private static List<Integer> graphicTicks(KillLog log, IdRegistry ids, Role role)
	{
		List<Integer> ticks = new ArrayList<>();
		for (GraphicObserved graphic : log.eventsOf(GraphicObserved.class))
		{
			if (ids.is(role, graphic.getGraphicId()))
			{
				ticks.add(graphic.getTick());
			}
		}
		for (GroundGraphicObserved graphic : log.eventsOf(GroundGraphicObserved.class))
		{
			if (ids.is(role, graphic.getGraphicId()))
			{
				ticks.add(graphic.getTick());
			}
		}
		return ticks;
	}

	private static Map<Actor, List<Integer>> playerGraphicTicks(KillLog log, IdRegistry ids, Role role)
	{
		Map<Actor, List<Integer>> ticks = new HashMap<>();
		for (GraphicObserved graphic : log.eventsOf(GraphicObserved.class))
		{
			if (graphic.getActor().isPlayer() && ids.is(role, graphic.getGraphicId()))
			{
				ticks.computeIfAbsent(graphic.getActor(), actor -> new ArrayList<>()).add(graphic.getTick());
			}
		}
		return ticks;
	}

	/** The meteor NPC's spawn and despawn both mark a Meteor Strike. */
	private static List<Integer> meteorNpcTicks(KillLog log, IdRegistry ids)
	{
		List<Integer> ticks = new ArrayList<>();
		for (NpcSpawnObserved spawn : log.eventsOf(NpcSpawnObserved.class))
		{
			if (ids.is(Role.METEOR_NPC, spawn.getNpcId()))
			{
				ticks.add(spawn.getTick());
			}
		}
		for (NpcDespawnObserved despawn : log.eventsOf(NpcDespawnObserved.class))
		{
			if (ids.is(Role.METEOR_NPC, despawn.getNpcId()))
			{
				ticks.add(despawn.getTick());
			}
		}
		return ticks;
	}

	/** True when some tick t has |tick - t| <= window. */
	private static boolean within(List<Integer> ticks, int tick, int window)
	{
		for (int candidate : ticks)
		{
			if (Math.abs(candidate - tick) <= window)
			{
				return true;
			}
		}
		return false;
	}

	/** The latest tick t with t <= tick <= t + window. */
	private static Optional<Integer> lastAtOrBefore(List<Integer> ticks, int tick, int window)
	{
		Integer latest = null;
		for (int candidate : ticks)
		{
			if (candidate <= tick && tick - candidate <= window)
			{
				latest = candidate;
			}
		}
		return Optional.ofNullable(latest);
	}

	/** A pool created within 1 tick of the hit, or earlier under the tile the player stands on at the hit. */
	private static boolean onAPool(HitsplatObserved hit, List<GroundGraphicObserved> pools, Map<Integer, TickState> states)
	{
		Position standingOn = positionOf(hit.getTarget(), states.get(hit.getTick()));
		for (GroundGraphicObserved pool : pools)
		{
			if (Math.abs(hit.getTick() - pool.getTick()) <= POOL_WINDOW_TICKS)
			{
				return true;
			}
			if (pool.getTick() <= hit.getTick() && standingOn != null && standingOn.equals(pool.getPosition()))
			{
				return true;
			}
		}
		return false;
	}

	private static Position positionOf(Actor player, TickState state)
	{
		if (state == null)
		{
			return null;
		}
		return player.equals(Actor.SELF) ? state.getSelfPosition() : state.getPartnerPosition();
	}
}
