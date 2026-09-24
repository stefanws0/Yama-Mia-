package com.yamareviewer.domain.review;

import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.Position;
import com.yamareviewer.domain.model.Phase;
import lombok.Value;

/** One line of three Shadow Crash fireballs (spec 6.6). */
@Value
public class CrashLine
{
	/** Tick the fireballs landed. */
	int tick;
	/** Null when the phases are unknown. */
	Phase phase;
	/** The player nearest the centre fireball. */
	Actor player;
	/** 1-based set number (lines within crashSetGap ticks of each other, across players). */
	int set;
	/** 1-based index of this line among the player's lines of the set. */
	int indexInSet;
	Position centre;
	boolean hit;
	int damage;
}
