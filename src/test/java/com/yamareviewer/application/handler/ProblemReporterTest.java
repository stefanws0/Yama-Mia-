package com.yamareviewer.application.handler;

import com.yamareviewer.application.port.ReportRepository;
import com.yamareviewer.domain.diagnosis.Versions;
import com.yamareviewer.domain.health.CheckOutcome;
import com.yamareviewer.domain.health.CheckResult;
import com.yamareviewer.domain.health.P3AttackCountCheck;
import com.yamareviewer.domain.health.PhaseOrderCheck;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.projection.ProjectionContext;
import com.yamareviewer.testing.CheckFights;
import com.yamareviewer.testing.TestIds;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class ProblemReporterTest
{
	private final FakeReports reports = new FakeReports();
	private final Clock clock = Clock.fixed(Instant.parse("2026-09-24T15:30:12.345Z"), ZoneOffset.UTC);
	private final ProblemReporter reporter = new ProblemReporter(reports, TestIds.registry(),
		() -> new Versions("0.1.0", "1.11.5", "232"), clock);

	@Test
	public void writesATimestampedReportWithCandidatesForEachFailedCheck()
	{
		KillLog kill = CheckFights.healthySolo();
		ProjectionContext context = CheckFights.context(kill);
		List<CheckOutcome> outcomes = List.of(
			new CheckOutcome(new PhaseOrderCheck(), CheckResult.fail("phases seen: P1", 40)),
			new CheckOutcome(new P3AttackCountCheck(), CheckResult.pass()));

		Optional<String> written = reporter.reportFailedChecks(kill, context, outcomes);

		assertEquals(Optional.of("20260924-153012-345.txt"), written);
		String text = reports.saved.get("20260924-153012-345.txt");
		assertTrue(text, text.startsWith("Yama Reviewer report: health checks failed (PhaseOrder)\nPlugin 0.1.0, RuneLite 1.11.5, game revision 232\n"));
		assertTrue(text, text.contains(", mode SOLO, No contract, "));
		assertTrue(text, text.contains("\n  FAILED  PhaseOrder: phases seen: P1"));
		assertTrue(text, text.contains("\n  passed  P3AttackCount\n"));
		assertTrue(text, text.contains("\n  PhaseOrder: JUDGE, PHASE_TRANSITION_TEXT, PHASE_VARBIT, PHASE_TRANSITION_GRAPHIC\n"));
		assertTrue(text, text.contains("\nEvents (lines "));
	}

	@Test
	public void theSelfCheckReportsEachNewIdOnceListingAllSeen()
	{
		assertEquals(Optional.of("20260924-153012-345.txt"), reporter.reportUnknownYama(14999));
		assertEquals(Optional.empty(), reporter.reportUnknownYama(14999));
		assertEquals(Optional.of("20260924-153012-345.txt"), reporter.reportUnknownYama(15001));

		String text = reports.saved.get("20260924-153012-345.txt");
		assertTrue(text, text.startsWith("Yama Reviewer report: unknown Yama NPC id [14999, 15001]\n"));
		assertTrue(text, text.contains("Built-in YAMA ids: [" + TestIds.id(com.yamareviewer.domain.ids.Role.YAMA) + "]\n"));
		assertEquals(2, reports.writes);
	}

	@Test
	public void aWriteFailureIsSwallowed()
	{
		reports.fail = true;

		assertEquals(Optional.empty(), reporter.reportUnknownYama(14999));
		assertEquals(Optional.empty(), reporter.reportFailedChecks(CheckFights.healthySolo(), CheckFights.context(CheckFights.healthySolo()),
			List.of(new CheckOutcome(new PhaseOrderCheck(), CheckResult.fail("x")))));
	}

	private static final class FakeReports implements ReportRepository
	{
		private final Map<String, String> saved = new LinkedHashMap<>();
		private int writes;
		private boolean fail;

		@Override
		public void save(String fileName, String text) throws IOException
		{
			if (fail)
			{
				throw new IOException("disk full");
			}
			writes++;
			saved.put(fileName, text);
		}

		@Override
		public Optional<String> latest()
		{
			return saved.values().stream().reduce((first, second) -> second);
		}
	}
}
