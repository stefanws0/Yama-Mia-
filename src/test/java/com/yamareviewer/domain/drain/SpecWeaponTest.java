package com.yamareviewer.domain.drain;

import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.testing.TestIds;
import java.util.Optional;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class SpecWeaponTest
{
	@Test
	public void resolvesWeaponsAndAnimationsThroughTheRegistry()
	{
		assertEquals(Optional.of(SpecWeapon.ELDER_MAUL), SpecWeapon.ofWeapon(TestIds.registry(), TestIds.id(Role.WEAPON_ELDER_MAUL)));
		assertEquals(Optional.of(SpecWeapon.PURGING_STAFF), SpecWeapon.ofAnimation(TestIds.registry(), TestIds.id(Role.SPEC_PURGING_STAFF)));
		assertEquals(Optional.empty(), SpecWeapon.ofWeapon(TestIds.registry(), 4151));
		assertEquals(Optional.empty(), SpecWeapon.ofAnimation(TestIds.registry(), 1));
	}

	@Test
	public void everyWeaponHasARoleOfEachKindAndAName()
	{
		for (SpecWeapon weapon : SpecWeapon.values())
		{
			assertTrue(weapon.name(), weapon.weaponRole().name().startsWith("WEAPON_"));
			assertTrue(weapon.name(), weapon.specRole().name().startsWith("SPEC_"));
			assertFalse(weapon.name(), weapon.displayName().isEmpty());
		}
	}

	@Test
	public void meleeAndDrainFlags()
	{
		assertTrue(SpecWeapon.ELDER_MAUL.isMelee());
		assertTrue(SpecWeapon.SARADOMIN_GODSWORD.isMelee());
		assertFalse(SpecWeapon.ACCURSED_SCEPTRE.isMelee());
		assertFalse(SpecWeapon.SOULFLAME_HORN.isMelee());
		assertTrue(SpecWeapon.EYE_OF_AYAK.drains());
		assertFalse(SpecWeapon.SOULFLAME_HORN.drains());
		assertFalse(SpecWeapon.PURGING_STAFF.drains());
		assertFalse(SpecWeapon.SARADOMIN_GODSWORD.drains());
	}
}
