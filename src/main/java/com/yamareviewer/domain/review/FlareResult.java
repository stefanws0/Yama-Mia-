package com.yamareviewer.domain.review;

import lombok.Value;

/** One void flare; despawnTick is null when the fight ended while it was alive. */
@Value
public class FlareResult
{
	int npcIndex;
	int spawnTick;
	Integer despawnTick;
	FlareCause cause;
	FlareFate fate;
	boolean killedByPurgingStaff;
}
