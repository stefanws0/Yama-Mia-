package com.yamareviewer.domain.projection;

import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.ActorKind;
import com.yamareviewer.domain.event.AnimationObserved;
import com.yamareviewer.domain.event.DomainEvent;
import com.yamareviewer.domain.event.GameStateKind;
import com.yamareviewer.domain.event.GameStateObserved;
import com.yamareviewer.domain.event.GraphicObserved;
import com.yamareviewer.domain.event.HitsplatKind;
import com.yamareviewer.domain.event.HitsplatObserved;
import com.yamareviewer.domain.event.NpcDespawnObserved;
import com.yamareviewer.domain.event.NpcSpawnObserved;
import com.yamareviewer.domain.ids.IdRegistry;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.review.FlareCause;
import com.yamareviewer.domain.review.FlareFate;
import com.yamareviewer.domain.review.FlareResult;
import com.yamareviewer.domain.review.FlareSummary;
import com.yamareviewer.domain.review.FlareWave;
import com.yamareviewer.domain.review.Section;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Spec 6.8. A flare is spawned by its first NpcSpawnObserved (a respawn inside a scene reload adds nothing);
 * waves are spawns within 3 ticks of each other; the cause is the latest YAMA_FLARE_SUMMON or YAMA_MELEE in
 * the 5 ticks before the spawn. Exploded: FLARE_EXPLODE on the flare, or a heal hitsplat on Yama or FLARE_HEAL
 * on Yama within 1 tick of the despawn. Killed: FLARE_DEATH or a dying despawn without an explosion; killed
 * by the purging staff when the last own hit on the flare lies within specResultWindow ticks after a
 * SPEC_PURGING_STAFF animation of the local player (never when that role is uncaptured).
 */
public final class FlaresProjection implements Projection<FlareSummary>
{
	static final int WAVE_GAP_TICKS = 3;
	static final int CAUSE_WINDOW_TICKS = 5;
	static final int EXPLOSION_WINDOW_TICKS = 1;

	@Override
	public SectionKey<FlareSummary> key()
	{
		return Sections.FLARES;
	}

	@Override
	public Set<Role> requiredRoles()
	{
		return EnumSet.of(Role.VOID_FLARE);
	}

	@Override
	public Section<FlareSummary> project(KillLog log, ProjectionContext context)
	{
		IdRegistry ids = context.ids();
		int specWindow = context.rules().getSpecResultWindow();
		Map<Integer, Tracked> flares = new LinkedHashMap<>();
		List<Integer> summonTicks = new ArrayList<>();
		List<Integer> meleeTicks = new ArrayList<>();
		List<Integer> purgingTicks = new ArrayList<>();
		List<Integer> explosionSignalTicks = new ArrayList<>();
		int yamaHealing = 0;
		boolean loading = false;

		for (DomainEvent event : log.getEvents())
		{
			int tick = event.getTick();
			if (event instanceof GameStateObserved)
			{
				loading = ((GameStateObserved) event).getState() == GameStateKind.LOADING;
			}
			else if (event instanceof NpcSpawnObserved)
			{
				NpcSpawnObserved spawn = (NpcSpawnObserved) event;
				if (isFlare(spawn.getActor(), spawn.getNpcId(), ids))
				{
					int index = indexOf(spawn.getActor(), spawn.getNpcIndex());
					flares.computeIfAbsent(index, i -> new Tracked(i, tick, causeOf(tick, summonTicks, meleeTicks)));
				}
			}
			else if (event instanceof NpcDespawnObserved)
			{
				NpcDespawnObserved despawn = (NpcDespawnObserved) event;
				Tracked flare = isFlare(despawn.getActor(), despawn.getNpcId(), ids) ? flares.get(indexOf(despawn.getActor(), despawn.getNpcIndex())) : null;
				if (flare != null && flare.despawnTick == null && (!loading || despawn.isDying()))
				{
					flare.despawnTick = tick;
					flare.dying = despawn.isDying();
				}
			}
			else if (event instanceof AnimationObserved)
			{
				AnimationObserved animation = (AnimationObserved) event;
				Actor actor = animation.getActor();
				int id = animation.getAnimationId();
				if (actor.equals(Actor.YAMA) && ids.is(Role.YAMA_FLARE_SUMMON, id))
				{
					summonTicks.add(tick);
				}
				else if (actor.equals(Actor.YAMA) && ids.is(Role.YAMA_MELEE, id))
				{
					meleeTicks.add(tick);
				}
				else if (actor.equals(Actor.SELF) && ids.is(Role.SPEC_PURGING_STAFF, id))
				{
					purgingTicks.add(tick);
				}
				else if (actor.getKind() == ActorKind.FLARE && flares.containsKey(actor.getRef()))
				{
					Tracked flare = flares.get(actor.getRef());
					if (ids.is(Role.FLARE_EXPLODE, id))
					{
						flare.exploded = true;
					}
					else if (ids.is(Role.FLARE_DEATH, id))
					{
						flare.deathAnimation = true;
					}
				}
			}
			else if (event instanceof HitsplatObserved)
			{
				HitsplatObserved hitsplat = (HitsplatObserved) event;
				Actor target = hitsplat.getTarget();
				if (target.getKind() == ActorKind.FLARE && hitsplat.getKind() == HitsplatKind.DAMAGE && hitsplat.isMine()
					&& flares.containsKey(target.getRef()))
				{
					flares.get(target.getRef()).lastOwnHitTick = tick;
				}
				else if (target.equals(Actor.YAMA) && hitsplat.getKind() == HitsplatKind.HEAL)
				{
					yamaHealing += hitsplat.getAmount();
					explosionSignalTicks.add(tick);
				}
			}
			else if (event instanceof GraphicObserved)
			{
				GraphicObserved graphic = (GraphicObserved) event;
				if (graphic.getActor().equals(Actor.YAMA) && ids.is(Role.FLARE_HEAL, graphic.getGraphicId()))
				{
					explosionSignalTicks.add(tick);
				}
			}
		}

		int fightEnd = log.lastTick();
		List<FlareResult> results = new ArrayList<>();
		for (Tracked flare : flares.values())
		{
			boolean exploded = flare.exploded
				|| (flare.despawnTick != null && near(explosionSignalTicks, flare.despawnTick, EXPLOSION_WINDOW_TICKS));
			FlareFate fate = exploded ? FlareFate.EXPLODED : (flare.deathAnimation || flare.dying) ? FlareFate.KILLED : FlareFate.UNKNOWN;
			boolean purging = fate == FlareFate.KILLED && flare.lastOwnHitTick != null
				&& purgingTicks.stream().anyMatch(spec -> flare.lastOwnHitTick >= spec && flare.lastOwnHitTick <= spec + specWindow);
			results.add(new FlareResult(flare.index, flare.spawnTick, flare.despawnTick, flare.cause, fate, purging));
		}

		List<FlareWave> waves = new ArrayList<>();
		List<FlareResult> current = new ArrayList<>();
		int previousSpawn = 0;
		for (FlareResult result : results)
		{
			if (!current.isEmpty() && result.getSpawnTick() - previousSpawn > WAVE_GAP_TICKS)
			{
				waves.add(wave(current, fightEnd));
				current = new ArrayList<>();
			}
			current.add(result);
			previousSpawn = result.getSpawnTick();
		}
		if (!current.isEmpty())
		{
			waves.add(wave(current, fightEnd));
		}
		return Section.ok(new FlareSummary(List.copyOf(waves), yamaHealing));
	}

	private static boolean isFlare(Actor actor, int npcId, IdRegistry ids)
	{
		return actor.getKind() == ActorKind.FLARE || ids.is(Role.VOID_FLARE, npcId);
	}

	private static int indexOf(Actor actor, int npcIndex)
	{
		return actor.getKind() == ActorKind.FLARE ? actor.getRef() : npcIndex;
	}

	/** The latest summon or melee animation in the window before the spawn; a tie goes to the summon. */
	private static FlareCause causeOf(int spawnTick, List<Integer> summonTicks, List<Integer> meleeTicks)
	{
		int summon = latestWithin(summonTicks, spawnTick, CAUSE_WINDOW_TICKS);
		int melee = latestWithin(meleeTicks, spawnTick, CAUSE_WINDOW_TICKS);
		if (summon < 0 && melee < 0)
		{
			return FlareCause.UNKNOWN;
		}
		return summon >= melee ? FlareCause.SUMMONED : FlareCause.MELEE;
	}

	/** The latest tick in [tick - window, tick], or -1. */
	private static int latestWithin(List<Integer> ticks, int tick, int window)
	{
		int latest = -1;
		for (int candidate : ticks)
		{
			if (candidate <= tick && tick - candidate <= window)
			{
				latest = candidate;
			}
		}
		return latest;
	}

	private static boolean near(List<Integer> ticks, int tick, int window)
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

	private static FlareWave wave(List<FlareResult> flares, int fightEnd)
	{
		int lastDespawn = fightEnd;
		for (FlareResult flare : flares)
		{
			lastDespawn = Math.max(flares.get(0).getSpawnTick(), flare.getDespawnTick() == null ? fightEnd : flare.getDespawnTick());
		}
		int firstSpawn = flares.get(0).getSpawnTick();
		int last = firstSpawn;
		for (FlareResult flare : flares)
		{
			last = Math.max(last, flare.getDespawnTick() == null ? fightEnd : flare.getDespawnTick());
		}
		return new FlareWave(firstSpawn, last, List.copyOf(flares));
	}

	/** Mutable bookkeeping for one flare while the events are scanned. */
	private static final class Tracked
	{
		private final int index;
		private final int spawnTick;
		private final FlareCause cause;
		private Integer despawnTick;
		private boolean dying;
		private boolean exploded;
		private boolean deathAnimation;
		private Integer lastOwnHitTick;

		private Tracked(int index, int spawnTick, FlareCause cause)
		{
			this.index = index;
			this.spawnTick = spawnTick;
			this.cause = cause;
		}
	}
}
