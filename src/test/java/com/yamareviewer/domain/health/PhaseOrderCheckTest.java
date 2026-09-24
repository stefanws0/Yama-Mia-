package com.yamareviewer.domain.health;

import com.yamareviewer.domain.contract.ContractRules;
import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.ids.Rules;
import com.yamareviewer.domain.model.Contract;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.model.Phase;
import com.yamareviewer.domain.projection.ProjectionContext;
import com.yamareviewer.domain.projection.ReviewSettings;
import com.yamareviewer.domain.projection.Sections;
import com.yamareviewer.domain.review.PhaseSpan;
import com.yamareviewer.domain.review.PhaseTimes;
import com.yamareviewer.domain.review.Section;
import com.yamareviewer.testing.CheckFights;
import com.yamareviewer.testing.KillLogBuilder;
import com.yamareviewer.testing.TestIds;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class PhaseOrderCheckTest
{
	private final PhaseOrderCheck check = new PhaseOrderCheck();

	private CheckResult run(KillLog log)
	{
		return check.check(log, CheckFights.context(log));
	}

	@Test
	public void aFullKillPasses()
	{
		assertEquals(CheckResult.pass(), run(CheckFights.healthySolo()));
	}

	@Test
	public void deathBeforeP3IsNotAFailure()
	{
		KillLogBuilder kill = CheckFights.solo().ticks(40);
		CheckFights.judgePhase(kill).ticks(10);

		assertEquals(CheckResult.pass(), run(CheckFights.finish(kill, EndReason.PLAYER_DIED)));
	}

	@Test
	public void leavingInP1IsNotAFailure()
	{
		assertEquals(CheckResult.pass(), run(CheckFights.solo().ticks(30).build()));
	}

	@Test
	public void aKillWhereYamaDiedWithoutAllPhasesFails()
	{
		KillLog log = CheckFights.finish(CheckFights.solo().ticks(50), EndReason.YAMA_DIED);

		CheckResult result = run(log);

		assertFalse(result.passed());
		assertTrue(result.detail(), result.detail().contains("phases seen: P1;"));
		assertTrue(result.detail(), result.detail().contains("expected P1, JUDGE_1, P2, JUDGE_2, P3"));
		assertEquals(OptionalInt.of(50), result.tick());
	}

	@Test
	public void phasesOutOfOrderFail()
	{
		KillLog log = CheckFights.solo().ticks(80).build();
		ProjectionContext context = new ProjectionContext(TestIds.registry(), Rules.DEFAULT, ReviewSettings.DEFAULT);
		context.put(Sections.PHASES, Section.ok(new PhaseTimes(
			List.of(new PhaseSpan(Phase.P1, 0, 40), new PhaseSpan(Phase.P2, 40, 80)), 80)));

		CheckResult result = check.check(log, context);

		assertFalse(result.passed());
		assertTrue(result.detail(), result.detail().contains("phases seen: P1, P2;"));
		assertEquals(OptionalInt.of(40), result.tick());
	}

	@Test
	public void skippedWhenPhasesAreNotAvailable()
	{
		ProjectionContext context = new ProjectionContext(TestIds.registry(), Rules.DEFAULT, ReviewSettings.DEFAULT);

		assertTrue(check.check(CheckFights.solo().build(), context).skipped());
	}

	@Test
	public void appliesUnderEveryContractAndHidesTheSectionsOfSpec8()
	{
		for (Contract contract : Contract.values())
		{
			assertTrue(contract.name(), check.applies(ContractRules.of(contract, Rules.DEFAULT)));
		}
		assertEquals("PhaseOrder", check.name());
		assertEquals(List.of(Sections.PHASES, Sections.DAMAGE, Sections.PRAYER_REVIEW, Sections.OPENER, Sections.TICK_LOG), check.hides());
		assertEquals(Set.of(Role.JUDGE, Role.PHASE_VARBIT, Role.PHASE_TRANSITION_TEXT, Role.PHASE_TRANSITION_GRAPHIC), check.reportRoles());
	}
}
