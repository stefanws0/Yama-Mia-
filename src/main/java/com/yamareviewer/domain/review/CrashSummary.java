package com.yamareviewer.domain.review;

import com.yamareviewer.domain.event.Actor;
import java.util.List;
import java.util.stream.Collectors;
import lombok.Value;

@Value
public class CrashSummary
{
	List<CrashLine> lines;

	public List<CrashLine> forPlayer(Actor player)
	{
		return lines.stream().filter(line -> line.getPlayer().equals(player)).collect(Collectors.toList());
	}

	public int total(Actor player)
	{
		return forPlayer(player).size();
	}

	public int dodged(Actor player)
	{
		return (int) forPlayer(player).stream().filter(line -> !line.isHit()).count();
	}

	public int damage(Actor player)
	{
		return forPlayer(player).stream().mapToInt(CrashLine::getDamage).sum();
	}

	public int sets()
	{
		return lines.stream().mapToInt(CrashLine::getSet).max().orElse(0);
	}
}
