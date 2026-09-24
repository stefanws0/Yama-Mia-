package com.yamareviewer.domain.projection;

import com.yamareviewer.domain.contract.ContractRules;
import com.yamareviewer.domain.drain.DrainModel;
import com.yamareviewer.domain.drain.SpecWeapon;
import com.yamareviewer.domain.drain.YamaStats;
import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.ActorKind;
import com.yamareviewer.domain.event.AnimationObserved;
import com.yamareviewer.domain.event.HitsplatKind;
import com.yamareviewer.domain.event.HitsplatObserved;
import com.yamareviewer.domain.event.TickState;
import com.yamareviewer.domain.ids.IdRegistry;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.ids.Rules;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.model.Phase;
import com.yamareviewer.domain.review.DrainStep;
import com.yamareviewer.domain.review.HornUse;
import com.yamareviewer.domain.review.PhaseTimes;
import com.yamareviewer.domain.review.Section;
import com.yamareviewer.domain.review.SpecOutcome;
import com.yamareviewer.domain.review.SpecResult;
import com.yamareviewer.domain.review.SpecSummary;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Special attacks and the drain model (spec 6.9). Your specs are spec-energy drops in the TickStates matched
 * with a SPEC_* animation on the same or the previous tick (a drop with a known weapon but no animation is
 * kept, unmatched, for the SpecsMatched check; a drop with any other weapon is an OTHER_WEAPON spec). The
 * purging staff is detected by its animation alone. Partner specs are their SPEC_* animations. The result
 * is the first hitsplat on the target within specResultWindow ticks. Landed specs drain in tick order.
 */
public final class SpecsProjection implements Projection<SpecSummary>
{
	static final int HORN_WINDOW = 10;
	static final String STAT_RESTORE = "Stat restore";

	@Override
	public SectionKey<SpecSummary> key()
	{
		return Sections.SPECS;
	}

	@Override
	public Set<Role> requiredRoles()
	{
		return Set.of();
	}

	@Override
	public Section<SpecSummary> project(KillLog log, ProjectionContext context)
	{
		IdRegistry ids = context.ids();
		Rules rules = context.rules();
		ContractRules contract = context.contractRules();
		Section<PhaseTimes> phases = context.section(Sections.PHASES);
		TreeMap<Integer, TickState> states = CrashesProjection.statesByTick(log);
		List<AnimationObserved> animations = log.eventsOf(AnimationObserved.class);
		List<HitsplatObserved> hitsplats = log.eventsOf(HitsplatObserved.class);
		List<Detected> detected = detect(ids, states, animations);

		DrainModel model = DrainModel.forFight(contract, rules);
		List<SpecResult> specs = new ArrayList<>();
		List<DrainStep> drains = new ArrayList<>();
		int lowest = model.base().getDefence();
		Phase lowestPhase = null;
		for (Detected spec : detected)
		{
			Phase phase = PhaseLookup.phaseAt(phases, spec.tick);
			SpecResult result = resolve(spec, phase, hitsplats, states, rules.getSpecResultWindow(), contract.isSpecsAlwaysHit());
			specs.add(result);
			if (result.getOutcome() == SpecOutcome.LANDED && spec.weapon != null && spec.weapon.drains())
			{
				YamaStats before = model.stats();
				YamaStats restored = model.advanceTo(spec.tick);
				if (!restored.equals(before))
				{
					drains.add(new DrainStep(spec.tick, phase, STAT_RESTORE, before, restored));
				}
				YamaStats after = model.apply(spec.tick, spec.weapon.drainRule(), result.getDamage());
				drains.add(new DrainStep(spec.tick, phase, spec.weapon.displayName(), restored, after));
				if (after.getDefence() < lowest)
				{
					lowest = after.getDefence();
					lowestPhase = phase;
				}
			}
		}
		YamaStats beforeEnd = model.stats();
		YamaStats end = model.advanceTo(log.lastTick());
		if (!end.equals(beforeEnd))
		{
			drains.add(new DrainStep(log.lastTick(), PhaseLookup.phaseAt(phases, log.lastTick()), STAT_RESTORE, beforeEnd, end));
		}
		return Section.ok(new SpecSummary(specs, horns(specs), drains, model.base(), end, lowest, lowestPhase));
	}

	private static List<Detected> detect(IdRegistry ids, TreeMap<Integer, TickState> states, List<AnimationObserved> animations)
	{
		List<Detected> detected = new ArrayList<>();
		Set<Integer> consumedDrops = new HashSet<>();
		for (AnimationObserved animation : animations)
		{
			if (Actor.SELF.equals(animation.getActor()) && ids.is(Role.SPEC_PURGING_STAFF, animation.getAnimationId()))
			{
				int energy = 0;
				for (int tick = animation.getTick(); tick <= animation.getTick() + 1; tick++)
				{
					int drop = dropAt(states, tick);
					if (drop > 0 && consumedDrops.add(tick))
					{
						energy = drop;
						break;
					}
				}
				detected.add(new Detected(animation.getTick(), Actor.SELF, SpecWeapon.PURGING_STAFF, true, energy));
			}
		}
		TickState previous = null;
		for (TickState state : states.values())
		{
			if (previous != null && state.getSpecEnergy() < previous.getSpecEnergy() && !consumedDrops.contains(state.getTick()))
			{
				int drop = previous.getSpecEnergy() - state.getSpecEnergy();
				AnimationObserved animation = ownSpecAnimation(ids, animations, state.getTick());
				if (animation != null)
				{
					SpecWeapon weapon = SpecWeapon.ofAnimation(ids, animation.getAnimationId()).orElse(null);
					detected.add(new Detected(animation.getTick(), Actor.SELF, weapon, true, drop));
				}
				else
				{
					SpecWeapon equipped = SpecWeapon.ofWeapon(ids, state.getWeaponId())
						.orElse(SpecWeapon.ofWeapon(ids, previous.getWeaponId()).orElse(null));
					detected.add(new Detected(state.getTick(), Actor.SELF, equipped, false, drop));
				}
			}
			previous = state;
		}
		for (AnimationObserved animation : animations)
		{
			if (Actor.PARTNER.equals(animation.getActor()))
			{
				Optional<SpecWeapon> weapon = SpecWeapon.ofAnimation(ids, animation.getAnimationId());
				if (weapon.isPresent())
				{
					detected.add(new Detected(animation.getTick(), Actor.PARTNER, weapon.get(), true, 0));
				}
			}
		}
		detected.sort(Comparator.comparingInt(spec -> spec.tick));
		return detected;
	}

	/** Your spec energy lost between the previous TickState and the one of this tick; 0 when it did not drop. */
	private static int dropAt(TreeMap<Integer, TickState> states, int tick)
	{
		TickState now = states.get(tick);
		Map.Entry<Integer, TickState> before = states.lowerEntry(tick);
		if (now == null || before == null)
		{
			return 0;
		}
		return Math.max(0, before.getValue().getSpecEnergy() - now.getSpecEnergy());
	}

	/** Your latest SPEC_* animation (purging staff excluded) on the drop tick or the tick before. */
	private static AnimationObserved ownSpecAnimation(IdRegistry ids, List<AnimationObserved> animations, int dropTick)
	{
		AnimationObserved found = null;
		for (AnimationObserved animation : animations)
		{
			if (!Actor.SELF.equals(animation.getActor()) || animation.getTick() < dropTick - 1 || animation.getTick() > dropTick)
			{
				continue;
			}
			Optional<SpecWeapon> weapon = SpecWeapon.ofAnimation(ids, animation.getAnimationId());
			if (!weapon.isPresent() || weapon.get() == SpecWeapon.PURGING_STAFF)
			{
				continue;
			}
			if (found == null || animation.getTick() > found.getTick())
			{
				found = animation;
			}
		}
		return found;
	}

	private static SpecResult resolve(Detected spec, Phase phase, List<HitsplatObserved> hitsplats, TreeMap<Integer, TickState> states,
		int window, boolean alwaysHit)
	{
		if (spec.weapon == SpecWeapon.SOULFLAME_HORN)
		{
			return new SpecResult(spec.tick, phase, spec.user, spec.weapon, spec.animationSeen, null, SpecOutcome.UNKNOWN, null, spec.energy, null, null);
		}
		boolean purging = spec.weapon == SpecWeapon.PURGING_STAFF;
		boolean mine = Actor.SELF.equals(spec.user);
		HitsplatObserved hit = null;
		for (HitsplatObserved hitsplat : hitsplats)
		{
			if (hitsplat.getTick() < spec.tick || hitsplat.getTick() > spec.tick + window || hitsplat.isMine() != mine
				|| (hitsplat.getKind() != HitsplatKind.DAMAGE && hitsplat.getKind() != HitsplatKind.BLOCK))
			{
				continue;
			}
			boolean rightTarget = purging ? hitsplat.getTarget().getKind() == ActorKind.FLARE : Actor.YAMA.equals(hitsplat.getTarget());
			if (rightTarget)
			{
				hit = hitsplat;
				break;
			}
		}
		if (hit == null)
		{
			return new SpecResult(spec.tick, phase, spec.user, spec.weapon, spec.animationSeen, purging ? null : Actor.YAMA,
				SpecOutcome.UNKNOWN, null, spec.energy, null, null);
		}
		SpecOutcome outcome = hit.getAmount() > 0 || alwaysHit ? SpecOutcome.LANDED : SpecOutcome.MISSED;
		Integer hpRestored = null;
		Integer prayerRestored = null;
		if (spec.weapon == SpecWeapon.SARADOMIN_GODSWORD && outcome == SpecOutcome.LANDED)
		{
			TickState after = states.get(hit.getTick());
			Map.Entry<Integer, TickState> before = states.lowerEntry(hit.getTick());
			if (after != null && before != null)
			{
				hpRestored = Math.max(0, after.getHitpoints() - before.getValue().getHitpoints());
				prayerRestored = Math.max(0, after.getPrayerPoints() - before.getValue().getPrayerPoints());
			}
		}
		return new SpecResult(spec.tick, phase, spec.user, spec.weapon, spec.animationSeen, hit.getTarget(), outcome, hit.getAmount(),
			spec.energy, hpRestored, prayerRestored);
	}

	/** Each horn blown, with the first melee spec by anyone within HORN_WINDOW ticks after it. */
	private static List<HornUse> horns(List<SpecResult> specs)
	{
		List<HornUse> horns = new ArrayList<>();
		for (SpecResult horn : specs)
		{
			if (horn.getWeapon() != SpecWeapon.SOULFLAME_HORN)
			{
				continue;
			}
			SpecResult assisted = null;
			for (SpecResult spec : specs)
			{
				if (spec.getWeapon() != null && spec.getWeapon().isMelee()
					&& spec.getTick() > horn.getTick() && spec.getTick() <= horn.getTick() + HORN_WINDOW)
				{
					assisted = spec;
					break;
				}
			}
			horns.add(new HornUse(horn.getTick(), horn.getUser(), assisted));
		}
		return horns;
	}

	private static final class Detected
	{
		private final int tick;
		private final Actor user;
		private final SpecWeapon weapon;
		private final boolean animationSeen;
		private final int energy;

		private Detected(int tick, Actor user, SpecWeapon weapon, boolean animationSeen, int energy)
		{
			this.tick = tick;
			this.user = user;
			this.weapon = weapon;
			this.animationSeen = animationSeen;
			this.energy = energy;
		}
	}
}
