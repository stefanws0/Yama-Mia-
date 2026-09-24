package com.yamareviewer.domain.review;

import java.util.List;
import lombok.Value;

/** Flares spawned within 3 ticks of each other; lastDespawnTick is the fight end for flares still alive. */
@Value
public class FlareWave
{
	int firstSpawnTick;
	int lastDespawnTick;
	List<FlareResult> flares;

	/** The cause of the first flare, UNKNOWN for an empty wave. */
	public FlareCause cause()
	{
		return flares.isEmpty() ? FlareCause.UNKNOWN : flares.get(0).getCause();
	}

	public int ticks()
	{
		return lastDespawnTick - firstSpawnTick;
	}
}
