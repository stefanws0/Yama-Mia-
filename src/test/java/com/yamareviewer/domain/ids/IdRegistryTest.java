package com.yamareviewer.domain.ids;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class IdRegistryTest
{
	private final IdRegistry registry = new IdRegistry(
		Map.of(
			Role.YAMA, Set.of(100),
			Role.YAMA_STANDARD_ATTACK, Set.of(200, 201),
			Role.IMPACT_MAGIC, Set.of()),
		Map.of(Role.PHASE_TRANSITION_TEXT, Set.of("Enough.")));

	@Test
	public void matchesIdsByRole()
	{
		assertTrue(registry.is(Role.YAMA, 100));
		assertFalse(registry.is(Role.YAMA, 200));
		assertEquals(Set.of(200, 201), registry.ids(Role.YAMA_STANDARD_ATTACK));
	}

	@Test
	public void emptyOrMissingRolesAreUncaptured()
	{
		assertTrue(registry.isCaptured(Role.YAMA));
		assertFalse(registry.isCaptured(Role.IMPACT_MAGIC));
		assertFalse(registry.isCaptured(Role.CRASH_FIREBALL));
		assertTrue(registry.isCaptured(Role.PHASE_TRANSITION_TEXT));
	}

	@Test
	public void findsTheRoleOfAnIdWithinAKind()
	{
		assertEquals(Optional.of(Role.YAMA_STANDARD_ATTACK), registry.roleOf(RoleKind.ANIMATION, 201));
		assertEquals(Optional.empty(), registry.roleOf(RoleKind.GRAPHIC, 201));
	}

	@Test
	public void matchesOverheadTextExactly()
	{
		assertTrue(registry.matchesText(Role.PHASE_TRANSITION_TEXT, "Enough."));
		assertFalse(registry.matchesText(Role.PHASE_TRANSITION_TEXT, "enough"));
		assertFalse(registry.matchesText(Role.PHASE_TRANSITION_TEXT, null));
	}

	@Test
	public void messagesMatchByPrefixWithoutColourTags()
	{
		IdRegistry messages = new IdRegistry(Map.of(), Map.of(Role.PRAYER_DISABLED_MESSAGE, Set.of("You've been injured")));

		assertTrue(messages.matchesText(Role.PRAYER_DISABLED_MESSAGE,
			"<col=ef1020>You've been injured and can't use protection prayers!</col>"));
		assertFalse(messages.matchesText(Role.PRAYER_DISABLED_MESSAGE, "Something else"));
		assertTrue(messages.isCaptured(Role.PRAYER_DISABLED_MESSAGE));
	}

	@Test
	public void fingerprintFollowsTheContent()
	{
		IdRegistry same = new IdRegistry(
			Map.of(Role.YAMA, Set.of(100), Role.YAMA_STANDARD_ATTACK, Set.of(201, 200)),
			Map.of(Role.PHASE_TRANSITION_TEXT, Set.of("Enough.")));
		IdRegistry changed = new IdRegistry(
			Map.of(Role.YAMA, Set.of(101), Role.YAMA_STANDARD_ATTACK, Set.of(200, 201)),
			Map.of(Role.PHASE_TRANSITION_TEXT, Set.of("Enough.")));

		assertEquals(registry.fingerprint(), same.fingerprint());
		assertNotEquals(registry.fingerprint(), changed.fingerprint());
	}

	@Test(expected = UnsupportedOperationException.class)
	public void idSetsCannotBeModified()
	{
		registry.ids(Role.YAMA).add(5);
	}
}
