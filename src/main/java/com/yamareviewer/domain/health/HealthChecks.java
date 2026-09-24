package com.yamareviewer.domain.health;

import java.util.List;

/** The checks of spec section 8, in table order. Adding a check means adding a class here. */
public final class HealthChecks
{
	private HealthChecks()
	{
	}

	public static List<HealthCheck> standard()
	{
		return List.of(
			new PhaseOrderCheck(),
			new P3AttackCountCheck(),
			new AlternationCheck(),
			new BlockedDamageCheck(),
			new GraphicVsBehaviourCheck(),
			new CrashSetsCheck(),
			new SpecsMatchedCheck(),
			new SuppliesSnapshotCheck());
	}
}
