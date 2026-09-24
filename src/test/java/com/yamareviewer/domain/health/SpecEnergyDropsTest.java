package com.yamareviewer.domain.health;

import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.ids.RoleKind;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.testing.KillLogBuilder;
import com.yamareviewer.testing.TestIds;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class SpecEnergyDropsTest
{
	private static final int EMBERLIGHT = TestIds.id(Role.WEAPON_EMBERLIGHT);
	private static final int EMBERLIGHT_SPEC = TestIds.id(Role.SPEC_EMBERLIGHT);

	private static List<SpecDrop> drops(KillLog log)
	{
		return SpecEnergyDrops.find(log, TestIds.registry());
	}

	@Test
	public void aDropWithTheWeaponsAnimationIsMatched()
	{
		KillLog log = KillLogBuilder.kill().weapon(EMBERLIGHT).spec(100).endTick()
			.animates(Actor.SELF, EMBERLIGHT_SPEC).spec(75).endTick()
			.end(EndReason.LEFT);

		List<SpecDrop> drops = drops(log);

		assertEquals(1, drops.size());
		assertEquals(new SpecDrop(1, 100, 75, EMBERLIGHT, Role.WEAPON_EMBERLIGHT, true), drops.get(0));
		assertFalse(drops.get(0).unmatchedKnownWeapon());
	}

	@Test
	public void aDropWithoutAnimationIsUnmatched()
	{
		KillLog log = KillLogBuilder.kill().weapon(EMBERLIGHT).spec(100).endTick().spec(75).endTick().end(EndReason.LEFT);

		List<SpecDrop> drops = drops(log);

		assertEquals(1, drops.size());
		assertTrue(drops.get(0).unmatchedKnownWeapon());
	}

	@Test
	public void theAnimationOneTickEarlierCounts()
	{
		KillLog log = KillLogBuilder.kill().weapon(EMBERLIGHT).spec(100).animates(Actor.SELF, EMBERLIGHT_SPEC).endTick()
			.spec(75).endTick().end(EndReason.LEFT);

		assertTrue(drops(log).get(0).isMatched());
	}

	@Test
	public void unknownWeaponsAndRegenerationAreNoEvidence()
	{
		KillLog log = KillLogBuilder.kill().weapon(4151).spec(100).endTick().spec(50).endTick().spec(60).endTick().end(EndReason.LEFT);

		List<SpecDrop> drops = drops(log);

		assertEquals(1, drops.size());
		assertNull(drops.get(0).getWeaponRole());
		assertEquals(4151, drops.get(0).getWeaponId());
		assertFalse(drops.get(0).unmatchedKnownWeapon());
	}

	@Test
	public void everyWeaponRoleIsASpecWeaponAndEverySpecRoleIsAnAnimation()
	{
		Set<Role> weapons = Arrays.stream(Role.values())
			.filter(role -> role.kind() == RoleKind.ITEM && !Role.CONTRACT_ITEMS.contains(role))
			.collect(Collectors.toSet());
		Set<Role> specs = Arrays.stream(Role.values())
			.filter(role -> role.kind() == RoleKind.ANIMATION && role.name().startsWith("SPEC_"))
			.collect(Collectors.toSet());

		assertEquals(weapons, SpecEnergyDrops.WEAPON_ROLES);
		assertEquals(specs, SpecEnergyDrops.SPEC_ROLES);
	}
}
