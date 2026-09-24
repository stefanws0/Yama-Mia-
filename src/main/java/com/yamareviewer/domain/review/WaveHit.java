package com.yamareviewer.domain.review;

import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.model.Phase;
import lombok.Value;

/** One Shadow Wave reaching one player (spec 6.7). */
@Value
public class WaveHit
{
	/** Tick the wave reached the player. */
	int tick;
	/** Null when the phases are unknown. */
	Phase phase;
	Actor player;
	boolean hit;
	int damage;
	/** Only ever true for you: the game said your protection prayers were disabled. */
	boolean prayersDisabled;
}
