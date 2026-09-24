package com.yamareviewer.domain.history;

import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.review.ReviewStatus;
import lombok.Builder;
import lombok.Value;

/** One kill in a history list. Fields filled by Part 3 are null until then. */
@Value
@Builder(toBuilder = true)
public class KillSummary
{
	String killId;
	long startEpochMs;
	/** The end tick of the fight; -1 when the phases section is hidden. */
	int killTicks;
	EndReason endReason;
	ReviewStatus status;
	/** Null when supplies are hidden. */
	Long cost;
	/** Part 3: blocked / scored attacks. */
	Double p3Accuracy;
	/** Part 3. */
	Integer defenceDrained;
	/** Part 3. */
	Double specLandedShare;
}
