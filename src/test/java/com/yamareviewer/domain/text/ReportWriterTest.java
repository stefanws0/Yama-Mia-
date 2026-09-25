package com.yamareviewer.domain.text;

import com.yamareviewer.domain.diagnosis.Candidate;
import com.yamareviewer.domain.diagnosis.CheckCandidates;
import com.yamareviewer.domain.diagnosis.ProblemReport;
import com.yamareviewer.domain.diagnosis.Versions;
import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.event.HitsplatKind;
import com.yamareviewer.domain.event.HitsplatObserved;
import com.yamareviewer.domain.event.PlayerLeft;
import com.yamareviewer.domain.event.SnapshotKind;
import com.yamareviewer.domain.health.AlternationCheck;
import com.yamareviewer.domain.health.CheckOutcome;
import com.yamareviewer.domain.health.CheckResult;
import com.yamareviewer.domain.health.PhaseOrderCheck;
import com.yamareviewer.domain.health.SuppliesSnapshotCheck;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.model.Contract;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.model.Mode;
import com.yamareviewer.testing.CheckFights;
import com.yamareviewer.testing.KillLogBuilder;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class ReportWriterTest
{
	private static final Versions VERSIONS = new Versions("0.1.0", "1.11.5", "232");

	private static ProblemReport failedPhaseOrder(KillLog log)
	{
		CheckOutcome failed = new CheckOutcome(new PhaseOrderCheck(),
			CheckResult.fail("phases seen: P1; expected P1, JUDGE_1, P2, JUDGE_2, P3 for a kill that ended with Yama's death", 50));
		CheckOutcome passed = new CheckOutcome(new SuppliesSnapshotCheck(), CheckResult.pass());
		CheckOutcome skipped = new CheckOutcome(new AlternationCheck(), CheckResult.skipped("not applicable under Contract of Shard Acquisition"));
		List<Candidate> candidates = List.of(
			new Candidate(Role.PHASE_TRANSITION_GRAPHIC, 3301, null, "YAMA", 2, 233, 540, 0.0, "seen 2 times"));
		return ProblemReport.forFailedChecks(VERSIONS, log, Mode.SOLO, Contract.NONE, List.of(failed, passed, skipped),
			List.of(new CheckCandidates("PhaseOrder", new PhaseOrderCheck().reportRoles(), candidates)));
	}

	@Test
	public void listsVersionsTheKillChecksCandidatesAndAnExcerpt()
	{
		KillLog log = CheckFights.solo().ticks(50).supplies(SnapshotKind.END).end(EndReason.YAMA_DIED);

		String report = ReportWriter.write(failedPhaseOrder(log));

		assertTrue(report, report.startsWith("Yama Reviewer report: health checks failed (PhaseOrder)\n"
			+ "Plugin 0.1.0, RuneLite 1.11.5, game revision 232\n"));
		assertTrue(report, report.contains(": ended YAMA_DIED at tick 50, "));
		assertTrue(report, report.contains(", mode SOLO, No contract, ids fingerprint "));
		assertTrue(report, report.contains("\nChecks\n  FAILED  PhaseOrder: phases seen: P1;"));
		assertTrue(report, report.contains(" (hides phases, damage, "));
		assertTrue(report, report.contains("\n  passed  SuppliesSnapshot\n"));
		assertTrue(report, report.contains("\n  skipped Alternation: not applicable under Contract of Shard Acquisition\n"));
		assertTrue(report, report.contains("\nCandidates (ids in this log that the plugin doesn't know, best fit first)\n"
			+ "  PhaseOrder: JUDGE, PHASE_TRANSITION_TEXT, PHASE_VARBIT, PHASE_TRANSITION_GRAPHIC\n"));
		assertTrue(report, report.contains("    JUDGE: none found (unknown NPCs are only recorded in capture mode)\n"));
		assertTrue(report, report.contains("    PHASE_VARBIT: none found (this kind is only recorded for ids that already have a role)\n"));
		assertTrue(report, report.contains("    PHASE_TRANSITION_GRAPHIC on YAMA: graphic 3301 x2, ticks 233-540, fit 0.00, seen 2 times\n"));
		assertTrue(report, report.contains("\nEvents (lines 1-"));
		assertTrue(report, report.contains(", around tick 50;"));
		assertTrue(report, report.contains("\n  0 entry TRAVEL\n  0 fight-start at (3200,3200,0)\n  0 supplies START "));
	}

	@Test
	public void theExcerptHasAtMost200EventsAroundTheFirstFailure()
	{
		KillLog log = CheckFights.solo().ticks(300).end(EndReason.YAMA_DIED);
		ProblemReport report = ProblemReport.forFailedChecks(VERSIONS, log, null, null,
			List.of(new CheckOutcome(new PhaseOrderCheck(), CheckResult.fail("x", 150))), List.of());

		String text = ReportWriter.write(report);

		List<String> lines = text.substring(text.indexOf("Events (")).lines().collect(Collectors.toList());
		assertEquals(201, lines.size());
		assertTrue(lines.get(0), lines.get(0).contains("lines 54-253 of 306 events, around tick 150"));
		assertTrue(lines.get(1), lines.get(1).startsWith("  50 tick "));
		assertTrue(lines.get(200), lines.get(200).startsWith("  249 tick "));
	}

	@Test
	public void neverContainsPlayerNames()
	{
		KillLogBuilder kill = CheckFights.solo().playerSeen("Zezima")
			.event(new HitsplatObserved(0, Actor.other("Stranger"), HitsplatKind.DAMAGE, 4, 1, false))
			.ticks(2)
			.event(new PlayerLeft(2, "Zezima"));

		String report = ReportWriter.write(failedPhaseOrder(kill.end(EndReason.LEFT)));

		assertFalse(report, report.contains("Zezima"));
		assertFalse(report, report.contains("Stranger"));
		assertTrue(report, report.contains("\n  0 fight-start at (3200,3200,0)\n"));
		assertTrue(report, report.contains("\n  0 player-seen PARTNER\n"));
		assertTrue(report, report.contains("\n  0 hitsplat OTHER DAMAGE 4 type=1 mine=false\n"));
		assertTrue(report, report.contains("\n  2 player-left PARTNER\n"));
	}

	@Test
	public void theSelfCheckReportListsTheIds()
	{
		String report = ReportWriter.write(ProblemReport.forUnknownYama(VERSIONS, Set.of(15001, 14999), Set.of(14176)));

		assertTrue(report, report.startsWith("Yama Reviewer report: unknown Yama NPC id [14999, 15001]\n"));
		assertTrue(report, report.contains("Built-in YAMA ids: [14176]\n"));
		assertFalse(report, report.contains("Checks"));
		assertFalse(report, report.contains("Events ("));
	}

	@Test
	public void summaryStopsBeforeTheEvents()
	{
		String report = ReportWriter.write(failedPhaseOrder(CheckFights.solo().ticks(50).end(EndReason.YAMA_DIED)));

		String summary = ReportWriter.summary(report);

		assertTrue(summary, summary.contains("\nChecks\n"));
		assertTrue(summary, summary.contains("\nCandidates ("));
		assertFalse(summary, summary.contains("Events ("));
		assertEquals(summary, ReportWriter.summary(summary));
	}
}
