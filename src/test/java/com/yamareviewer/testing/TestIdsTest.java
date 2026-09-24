package com.yamareviewer.testing;

import com.yamareviewer.domain.ids.IdRegistry;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.ids.RoleKind;
import java.util.HashSet;
import java.util.Set;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class TestIdsTest
{
	@Test
	public void everyRoleIsCapturedWithAUniqueIdOrItsRealText()
	{
		IdRegistry ids = TestIds.registry();
		Set<Integer> seen = new HashSet<>();
		for (Role role : Role.values())
		{
			assertTrue(role.name(), ids.isCaptured(role));
			if (role.kind() != RoleKind.OVERHEAD && role.kind() != RoleKind.MESSAGE)
			{
				assertTrue(role.name(), seen.add(TestIds.id(role)));
				assertTrue(role.name(), ids.is(role, TestIds.id(role)));
			}
		}
		assertTrue(ids.matchesText(Role.PHASE_TRANSITION_TEXT, TestIds.text(Role.PHASE_TRANSITION_TEXT)));
		assertTrue(ids.matchesText(Role.PHASE_TRANSITION_TEXT, "Begone"));
		assertTrue(ids.matchesText(Role.PRAYER_DISABLED_MESSAGE, "<col=ef1020>" + TestIds.text(Role.PRAYER_DISABLED_MESSAGE) + "</col>"));
		assertTrue(ids.matchesText(Role.GLYPH_CONJURE_MESSAGE, "Yama conjures a Glyph of Fire."));
	}

	@Test
	public void registryWithoutLeavesRolesUncaptured()
	{
		IdRegistry ids = TestIds.registryWithout(Role.JUDGE, Role.PHASE_TRANSITION_TEXT);

		assertFalse(ids.isCaptured(Role.JUDGE));
		assertFalse(ids.isCaptured(Role.PHASE_TRANSITION_TEXT));
		assertTrue(ids.isCaptured(Role.YAMA));
	}
}
