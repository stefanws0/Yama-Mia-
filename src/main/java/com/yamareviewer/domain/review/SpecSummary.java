package com.yamareviewer.domain.review;

import com.yamareviewer.domain.drain.SpecWeapon;
import com.yamareviewer.domain.drain.YamaStats;
import com.yamareviewer.domain.model.Phase;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.Value;

/** Special attacks, horns, and the modelled drains of one kill (spec 6.9). */
@Value
public class SpecSummary
{
	List<SpecResult> specs;
	List<HornUse> horns;
	List<DrainStep> drains;
	/** The fight's base stats. */
	YamaStats base;
	/** The modelled stats at the end of the fight, after restores. */
	YamaStats finalStats;
	/** The lowest modelled Defence of the fight. */
	int lowestDefence;
	/** The phase in which the lowest Defence was first reached; null when nothing was drained. */
	Phase lowestDefencePhase;

	public List<SpecResult> ownSpecs()
	{
		return specs.stream().filter(SpecResult::isOwn).collect(Collectors.toList());
	}

	/** In order of first use, own and partner specs alike; OTHER_WEAPON specs are not weapons. */
	public List<SpecWeapon> weaponsUsed()
	{
		List<SpecWeapon> weapons = new ArrayList<>();
		for (SpecResult spec : specs)
		{
			if (spec.getWeapon() != null && !weapons.contains(spec.getWeapon()))
			{
				weapons.add(spec.getWeapon());
			}
		}
		return weapons;
	}

	public int used(SpecWeapon weapon)
	{
		return (int) specs.stream().filter(spec -> spec.getWeapon() == weapon).count();
	}

	public int landed(SpecWeapon weapon)
	{
		return (int) specs.stream().filter(spec -> spec.getWeapon() == weapon && spec.getOutcome() == SpecOutcome.LANDED).count();
	}

	public int otherWeaponSpecs()
	{
		return (int) specs.stream().filter(SpecResult::isOtherWeapon).count();
	}

	/** Spec energy you spent on weapons that drain, for "energy per Defence point drained". */
	public int ownEnergyUsedOnDrains()
	{
		return ownSpecs().stream()
			.filter(spec -> spec.getWeapon() != null && spec.getWeapon().drains())
			.mapToInt(SpecResult::getEnergyUsed)
			.sum();
	}

	public int defenceDrained()
	{
		return base.getDefence() - lowestDefence;
	}

	public int maxDefenceDrain()
	{
		return base.getDefence() - YamaStats.DEFENCE_FLOOR;
	}

	/** Landed out of your specs with a known outcome; empty when there are none. */
	public Optional<Double> landedShare()
	{
		List<SpecResult> known = ownSpecs().stream().filter(spec -> spec.getOutcome() != SpecOutcome.UNKNOWN).collect(Collectors.toList());
		if (known.isEmpty())
		{
			return Optional.empty();
		}
		long landed = known.stream().filter(spec -> spec.getOutcome() == SpecOutcome.LANDED).count();
		return Optional.of((double) landed / known.size());
	}
}
