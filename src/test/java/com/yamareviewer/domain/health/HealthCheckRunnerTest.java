package com.yamareviewer.domain.health;

import com.yamareviewer.domain.contract.ContractRules;
import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.ids.Rules;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.projection.ProjectionContext;
import com.yamareviewer.domain.projection.ReviewSettings;
import com.yamareviewer.domain.projection.SectionKey;
import com.yamareviewer.domain.projection.Sections;
import com.yamareviewer.domain.review.DamageSummary;
import com.yamareviewer.domain.review.HiddenReason;
import com.yamareviewer.domain.review.PhaseTimes;
import com.yamareviewer.domain.review.Section;
import com.yamareviewer.testing.KillLogBuilder;
import com.yamareviewer.testing.TestIds;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class HealthCheckRunnerTest
{
	private final KillLog log = KillLogBuilder.kill().ticks(3).end(EndReason.YAMA_DIED);
	private final ProjectionContext context = new ProjectionContext(TestIds.registry(), Rules.DEFAULT, ReviewSettings.DEFAULT);

	private static Section<PhaseTimes> phases()
	{
		return Section.ok(new PhaseTimes(List.of(), 0));
	}

	private static Section<DamageSummary> damage()
	{
		return Section.ok(new DamageSummary(List.of()));
	}

	@Test
	public void aFailedCheckHidesItsSectionsWithHealthCheckFailed()
	{
		context.put(Sections.PHASES, phases());
		context.put(Sections.DAMAGE, damage());
		FakeCheck check = new FakeCheck("Fake", List.of(Sections.PHASES, Sections.DAMAGE), true, CheckResult.fail("2 of 3", 7));

		List<CheckOutcome> outcomes = HealthCheckRunner.apply(log, context, List.of(check));

		assertEquals(Optional.of(HiddenReason.HEALTH_CHECK_FAILED), context.section(Sections.PHASES).hiddenReason());
		assertEquals(Optional.of(HiddenReason.HEALTH_CHECK_FAILED), context.section(Sections.DAMAGE).hiddenReason());
		assertEquals(1, outcomes.size());
		assertTrue(outcomes.get(0).failed());
		assertEquals("Fake", outcomes.get(0).name());
		assertEquals(CheckResult.fail("2 of 3", 7), outcomes.get(0).getResult());
	}

	@Test
	public void passingChecksChangeNothing()
	{
		context.put(Sections.PHASES, phases());
		FakeCheck check = new FakeCheck("Fake", List.of(Sections.PHASES), true, CheckResult.pass());

		List<CheckOutcome> outcomes = HealthCheckRunner.apply(log, context, List.of(check));

		assertTrue(context.section(Sections.PHASES).isOk());
		assertFalse(outcomes.get(0).failed());
		assertEquals(1, check.calls);
	}

	@Test
	public void sectionsHiddenForOtherReasonsAreLeftAlone()
	{
		context.put(Sections.PHASES, Section.hidden(HiddenReason.ERROR));
		context.put(Sections.DAMAGE, damage());
		FakeCheck check = new FakeCheck("Fake", List.of(Sections.PHASES, Sections.DAMAGE), true, CheckResult.fail("bad"));

		HealthCheckRunner.apply(log, context, List.of(check));

		assertEquals(Optional.of(HiddenReason.ERROR), context.section(Sections.PHASES).hiddenReason());
		assertEquals(Optional.of(HiddenReason.HEALTH_CHECK_FAILED), context.section(Sections.DAMAGE).hiddenReason());
	}

	@Test
	public void aCheckIsSkippedWhenEverySectionItProtectsIsHiddenForIdsApplicabilityOrContract()
	{
		context.put(Sections.PHASES, Section.hidden(HiddenReason.IDS_NOT_CAPTURED));
		context.put(Sections.DAMAGE, Section.hidden(HiddenReason.CONTRACT));
		FakeCheck check = new FakeCheck("Fake", List.of(Sections.PHASES, Sections.DAMAGE, Sections.FLARES), true, CheckResult.fail("bad"));

		List<CheckOutcome> outcomes = HealthCheckRunner.apply(log, context, List.of(check));

		assertEquals(0, check.calls);
		assertTrue(outcomes.get(0).skipped());
		assertFalse(outcomes.get(0).failed());
		assertTrue(outcomes.get(0).getResult().detail(), outcomes.get(0).getResult().detail().contains("already hidden"));
		assertEquals(Optional.of(HiddenReason.NOT_APPLICABLE), context.section(Sections.FLARES).hiddenReason());
	}

	@Test
	public void aCheckStillRunsWhenAnEarlierCheckHidItsSections()
	{
		context.put(Sections.PHASES, Section.hidden(HiddenReason.HEALTH_CHECK_FAILED));
		FakeCheck check = new FakeCheck("Fake", List.of(Sections.PHASES), true, CheckResult.fail("bad"));

		List<CheckOutcome> outcomes = HealthCheckRunner.apply(log, context, List.of(check));

		assertEquals(1, check.calls);
		assertTrue(outcomes.get(0).failed());
	}

	@Test
	public void aCheckThatDoesNotApplyUnderTheContractIsSkipped()
	{
		context.put(Sections.PHASES, phases());
		FakeCheck check = new FakeCheck("Fake", List.of(Sections.PHASES), false, CheckResult.fail("bad"));

		List<CheckOutcome> outcomes = HealthCheckRunner.apply(log, context, List.of(check));

		assertEquals(0, check.calls);
		assertTrue(outcomes.get(0).skipped());
		assertTrue(outcomes.get(0).getResult().detail(), outcomes.get(0).getResult().detail().contains("not applicable under No contract"));
		assertTrue(context.section(Sections.PHASES).isOk());
	}

	@Test
	public void anExceptionIsSkippedAndTheOtherChecksStillRun()
	{
		context.put(Sections.PHASES, phases());
		FakeCheck broken = new FakeCheck("Broken", List.of(Sections.PHASES), true, null);
		FakeCheck fine = new FakeCheck("Fine", List.of(Sections.PHASES), true, CheckResult.pass());

		List<CheckOutcome> outcomes = HealthCheckRunner.apply(log, context, List.of(broken, fine));

		assertEquals(2, outcomes.size());
		assertTrue(outcomes.get(0).skipped());
		assertTrue(outcomes.get(0).getResult().detail(), outcomes.get(0).getResult().detail().contains("error"));
		assertEquals(1, fine.calls);
		assertTrue(context.section(Sections.PHASES).isOk());
	}

	@Test
	public void failedNamesKeepTheCheckOrder()
	{
		List<CheckOutcome> outcomes = List.of(
			new CheckOutcome(new FakeCheck("A", List.of(), true, CheckResult.fail("a")), CheckResult.fail("a")),
			new CheckOutcome(new FakeCheck("B", List.of(), true, CheckResult.pass()), CheckResult.pass()),
			new CheckOutcome(new FakeCheck("C", List.of(), true, CheckResult.fail("c")), CheckResult.fail("c")));

		assertEquals(List.of("A", "C"), CheckOutcome.failedNames(outcomes));
		assertTrue(CheckOutcome.anyFailed(outcomes));
		assertFalse(CheckOutcome.anyFailed(outcomes.subList(1, 2)));
	}

	private static final class FakeCheck implements HealthCheck
	{
		private final String name;
		private final List<SectionKey<?>> hides;
		private final boolean applies;
		/** Null makes check() throw. */
		private final CheckResult result;
		private int calls;

		private FakeCheck(String name, List<SectionKey<?>> hides, boolean applies, CheckResult result)
		{
			this.name = name;
			this.hides = hides;
			this.applies = applies;
			this.result = result;
		}

		@Override
		public String name()
		{
			return name;
		}

		@Override
		public Set<Role> reportRoles()
		{
			return Set.of(Role.JUDGE);
		}

		@Override
		public List<SectionKey<?>> hides()
		{
			return hides;
		}

		@Override
		public boolean applies(ContractRules rules)
		{
			return applies;
		}

		@Override
		public CheckResult check(KillLog log, ProjectionContext context)
		{
			calls++;
			if (result == null)
			{
				throw new IllegalStateException("boom");
			}
			return result;
		}
	}
}
