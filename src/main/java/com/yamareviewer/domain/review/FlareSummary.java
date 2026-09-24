package com.yamareviewer.domain.review;

import java.util.List;
import java.util.stream.Stream;
import lombok.Value;

@Value
public class FlareSummary
{
	List<FlareWave> waves;
	/** Yama's healing from explosions: every HEAL hitsplat on him during the fight. */
	int yamaHealing;

	public int spawned()
	{
		return (int) flares().count();
	}

	public int killed()
	{
		return (int) flares().filter(flare -> flare.getFate() == FlareFate.KILLED).count();
	}

	public int exploded()
	{
		return (int) flares().filter(flare -> flare.getFate() == FlareFate.EXPLODED).count();
	}

	public int purgingStaffKills()
	{
		return (int) flares().filter(FlareResult::isKilledByPurgingStaff).count();
	}

	private Stream<FlareResult> flares()
	{
		return waves.stream().flatMap(wave -> wave.getFlares().stream());
	}
}
