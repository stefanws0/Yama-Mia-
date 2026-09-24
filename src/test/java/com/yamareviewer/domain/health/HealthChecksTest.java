package com.yamareviewer.domain.health;

import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.model.Contract;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.projection.ProjectionContext;
import com.yamareviewer.domain.projection.Sections;
import com.yamareviewer.testing.CheckFights;
import com.yamareviewer.testing.KillLogBuilder;
import com.yamareviewer.testing.TestIds;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class HealthChecksTest
{
	private static List<String> names(List<CheckOutcome> outcomes)
	{
		return outcomes.stream().map(CheckOutcome::name).collect(Collectors.toList());
	}

	@Test
	public void theEightChecksOfSpec8InTableOrder()
	{
		assertEquals(List.of("PhaseOrder", "P3AttackCount", "Alternation", "BlockedDamage", "GraphicVsBehaviour",
			"CrashSets", "SpecsMatched", "SuppliesSnapshot"),
			HealthChecks.standard().stream().map(HealthCheck::name).collect(Collectors.toList()));
	}

	@Test
	public void everyStandardCheckRunsAndPassesOnAHealthyKill()
	{
		KillLog log = CheckFights.healthySolo();
		ProjectionContext context = CheckFights.context(log);

		List<CheckOutcome> outcomes = HealthCheckRunner.apply(log, context, HealthChecks.standard());

		assertEquals(List.of(), CheckOutcome.failedNames(outcomes));
		assertEquals(List.of(), outcomes.stream().filter(CheckOutcome::skipped).map(CheckOutcome::name).collect(Collectors.toList()));
		assertTrue(context.section(Sections.PRAYER_REVIEW).isOk());
	}

	@Test
	public void contractChecksAreSkippedUnderSensoryClouding()
	{
		KillLogBuilder kill = CheckFights.solo().widgetText(TestIds.id(Role.CONTRACT_NAME_WIDGET), "Contract of Sensory Clouding");
		CheckFights.untilP3(kill);
		CheckFights.blockedAttacks(kill, 20);
		KillLog log = CheckFights.finish(kill, EndReason.YAMA_DIED);
		ProjectionContext context = CheckFights.context(log);
		assertEquals(Optional.of(Contract.SENSORY_CLOUDING), context.value(Sections.CONTRACT));

		List<CheckOutcome> outcomes = HealthCheckRunner.apply(log, context, HealthChecks.standard());

		assertEquals(List.of(), CheckOutcome.failedNames(outcomes));
		assertEquals(List.of("BlockedDamage", "GraphicVsBehaviour"),
			outcomes.stream().filter(CheckOutcome::skipped).map(CheckOutcome::name).collect(Collectors.toList()));
		assertTrue(outcomes.get(3).getResult().detail(), outcomes.get(3).getResult().detail().contains("not applicable under Contract of Sensory Clouding"));
		assertEquals(8, names(outcomes).size());
	}
}
