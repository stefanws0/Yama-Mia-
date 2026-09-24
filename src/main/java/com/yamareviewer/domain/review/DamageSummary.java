package com.yamareviewer.domain.review;

import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.model.Phase;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import lombok.Value;

@Value
public class DamageSummary
{
	List<DamageHit> hits;

	public int total(Actor player)
	{
		return hits.stream().filter(hit -> hit.getPlayer().equals(player)).mapToInt(DamageHit::getAmount).sum();
	}

	public int total(Actor player, Phase phase)
	{
		return hits.stream()
			.filter(hit -> hit.getPlayer().equals(player) && hit.getPhase() == phase)
			.mapToInt(DamageHit::getAmount)
			.sum();
	}

	/** In declaration order of DamageSource; sources without damage are omitted. */
	public Map<DamageSource, Integer> bySource(Actor player)
	{
		Map<DamageSource, Integer> result = new EnumMap<>(DamageSource.class);
		for (DamageHit hit : hits)
		{
			if (hit.getPlayer().equals(player))
			{
				result.merge(hit.getSource(), hit.getAmount(), Integer::sum);
			}
		}
		return result;
	}

	/** Damage from shared mechanics, both players together. */
	public int shared()
	{
		return hits.stream().filter(hit -> hit.getSource().shared()).mapToInt(DamageHit::getAmount).sum();
	}
}
