package com.yamareviewer.domain.diagnosis;

import com.yamareviewer.domain.ids.Role;
import lombok.Value;

/** An id (or overhead text) the registry doesn't know that could be the given role after a game update. */
@Value
public class Candidate
{
	Role role;
	/** The unknown id; 0 for a text candidate. */
	int id;
	/** The unknown overhead text; null for an id candidate. */
	String text;
	/** Where it was seen: YAMA, SELF, PARTNER, JUDGE, FLARE, NPC, OTHER or ground. */
	String where;
	int count;
	int firstTick;
	int lastTick;
	/** 0 to 1: how well the occurrences fit the role's pattern (spec 9). */
	double score;
	/** The counts and tick offsets behind the score. */
	String evidence;
}
