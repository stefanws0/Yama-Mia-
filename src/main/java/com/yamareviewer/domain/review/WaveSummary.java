package com.yamareviewer.domain.review;

import com.yamareviewer.domain.event.Actor;
import java.util.List;
import java.util.stream.Collectors;
import lombok.Value;

@Value
public class WaveSummary
{
	List<WaveHit> waves;

	public List<WaveHit> forPlayer(Actor player)
	{
		return waves.stream().filter(wave -> wave.getPlayer().equals(player)).collect(Collectors.toList());
	}

	public int total(Actor player)
	{
		return forPlayer(player).size();
	}

	public int dodged(Actor player)
	{
		return (int) forPlayer(player).stream().filter(wave -> !wave.isHit()).count();
	}

	public int damage(Actor player)
	{
		return forPlayer(player).stream().mapToInt(WaveHit::getDamage).sum();
	}

	public int prayersDisabled(Actor player)
	{
		return (int) forPlayer(player).stream().filter(WaveHit::isPrayersDisabled).count();
	}
}
