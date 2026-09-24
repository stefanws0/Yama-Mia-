package com.yamareviewer.domain.health;

import com.yamareviewer.domain.contract.ContractRules;
import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.event.SnapshotKind;
import com.yamareviewer.domain.event.SuppliesSnapshot;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.projection.ProjectionContext;
import com.yamareviewer.domain.projection.SectionKey;
import com.yamareviewer.domain.projection.Sections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Spec 8: START and END snapshots must both be in the log; a LEFT kill is not checked. */
public final class SuppliesSnapshotCheck implements HealthCheck
{
	@Override
	public String name()
	{
		return "SuppliesSnapshot";
	}

	@Override
	public Set<Role> reportRoles()
	{
		return Set.of();
	}

	@Override
	public List<SectionKey<?>> hides()
	{
		return List.of(Sections.SUPPLIES);
	}

	@Override
	public boolean applies(ContractRules rules)
	{
		return true;
	}

	@Override
	public CheckResult check(KillLog log, ProjectionContext context)
	{
		if (log.endReason() == EndReason.LEFT)
		{
			return CheckResult.skipped("not checked for a kill that ended with LEFT");
		}
		Set<SnapshotKind> kinds = EnumSet.noneOf(SnapshotKind.class);
		for (SuppliesSnapshot snapshot : log.eventsOf(SuppliesSnapshot.class))
		{
			kinds.add(snapshot.getKind());
		}
		if (!kinds.contains(SnapshotKind.START))
		{
			return CheckResult.fail("no START supplies snapshot in the log", 0);
		}
		if (!kinds.contains(SnapshotKind.END))
		{
			return CheckResult.fail("no END supplies snapshot in the log", log.lastTick());
		}
		return CheckResult.pass();
	}
}
