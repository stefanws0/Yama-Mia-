package com.yamareviewer.domain.diagnosis;

import com.yamareviewer.domain.health.CheckOutcome;
import com.yamareviewer.domain.model.Contract;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.model.Mode;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import lombok.Value;

/** Everything a report says, before it is turned into text (spec 9). */
@Value
public class ProblemReport
{
	/** One line, the issue title: "health checks failed (P3AttackCount)" or "unknown Yama NPC id [14999]". */
	String headline;
	Versions versions;
	/** The kill the failed checks came from; null for the recording self-check. */
	KillLog log;
	/** Null when unknown. */
	Mode mode;
	/** Null when unknown. */
	Contract contract;
	List<CheckOutcome> outcomes;
	List<CheckCandidates> candidates;
	/** Free lines printed after the kill line. */
	List<String> notes;

	public static ProblemReport forFailedChecks(Versions versions, KillLog log, Mode mode, Contract contract,
		List<CheckOutcome> outcomes, List<CheckCandidates> candidates)
	{
		String failed = String.join(", ", CheckOutcome.failedNames(outcomes));
		return new ProblemReport("health checks failed (" + failed + ")", versions, log, mode, contract,
			List.copyOf(outcomes), List.copyOf(candidates), List.of());
	}

	/** Spec 5.3 and 9: an NPC named "Yama" with an unknown id spawned while armed, so no kill can be recorded. */
	public static ProblemReport forUnknownYama(Versions versions, Set<Integer> unknownIds, Set<Integer> knownIds)
	{
		String unknown = new TreeSet<>(unknownIds).toString();
		List<String> notes = List.of(
			"An NPC named \"Yama\" spawned in Yama's Domain with an id this plugin doesn't know: " + unknown,
			"Built-in YAMA ids: " + new TreeSet<>(knownIds),
			"No kill can be recorded until BuiltInIds knows the new id; a RuneLite update that regenerates gameval may already fix it.");
		return new ProblemReport("unknown Yama NPC id " + unknown, versions, null, null, null, List.of(), List.of(), notes);
	}
}
