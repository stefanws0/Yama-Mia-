package com.yamareviewer.domain.health;

import com.yamareviewer.domain.contract.ContractRules;
import com.yamareviewer.domain.event.DomainEvent;
import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.event.FightEnded;
import com.yamareviewer.domain.event.FightStarted;
import com.yamareviewer.domain.event.Position;
import com.yamareviewer.domain.event.SnapshotKind;
import com.yamareviewer.domain.event.SuppliesSnapshot;
import com.yamareviewer.domain.ids.Rules;
import com.yamareviewer.domain.model.Contract;
import com.yamareviewer.domain.model.KillHeader;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.projection.ProjectionContext;
import com.yamareviewer.domain.projection.ReviewSettings;
import com.yamareviewer.domain.projection.Sections;
import com.yamareviewer.testing.CheckFights;
import com.yamareviewer.testing.TestIds;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class SuppliesSnapshotCheckTest
{
	private final SuppliesSnapshotCheck check = new SuppliesSnapshotCheck();
	private final ProjectionContext context = new ProjectionContext(TestIds.registry(), Rules.DEFAULT, ReviewSettings.DEFAULT);

	@Test
	public void bothSnapshotsPass()
	{
		assertEquals(CheckResult.pass(), check.check(CheckFights.healthySolo(), context));
	}

	@Test
	public void aMissingEndSnapshotFails()
	{
		// KillLogBuilder.end adds an END snapshot when there is none, so the kill is ended by hand.
		KillLog unfinished = CheckFights.solo().ticks(5).build();
		List<DomainEvent> events = new ArrayList<>(unfinished.getEvents());
		events.add(new FightEnded(5, EndReason.YAMA_DIED));

		CheckResult result = check.check(KillLog.of(unfinished.getHeader(), events, 0), context);

		assertFalse(result.passed());
		assertTrue(result.detail(), result.detail().contains("END"));
		assertEquals(OptionalInt.of(5), result.tick());
	}

	@Test
	public void aMissingStartSnapshotFails()
	{
		KillHeader header = new KillHeader("k", 1L, 2L, "0.1.0", KillLog.SCHEMA_VERSION, "f00d", false);
		KillLog log = KillLog.of(header, List.of(
			new FightStarted(0, "Me", new Position(3200, 3200, 0)),
			new SuppliesSnapshot(4, SnapshotKind.END, List.of()),
			new FightEnded(4, EndReason.PLAYER_DIED)), 0);

		CheckResult result = check.check(log, context);

		assertFalse(result.passed());
		assertTrue(result.detail(), result.detail().contains("START"));
		assertEquals(OptionalInt.of(0), result.tick());
	}

	@Test
	public void leftKillsAreNotChecked()
	{
		CheckResult result = check.check(CheckFights.solo().ticks(5).end(EndReason.LEFT), context);

		assertTrue(result.passed());
		assertTrue(result.skipped());
	}

	@Test
	public void hidesSuppliesAndNamesNoRoles()
	{
		assertEquals("SuppliesSnapshot", check.name());
		assertEquals(List.of(Sections.SUPPLIES), check.hides());
		assertEquals(Set.of(), check.reportRoles());
		assertTrue(check.applies(ContractRules.of(Contract.FAMILIAR_ACQUISITION, Rules.DEFAULT)));
	}
}
