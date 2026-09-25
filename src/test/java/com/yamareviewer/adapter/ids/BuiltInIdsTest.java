package com.yamareviewer.adapter.ids;

import com.yamareviewer.domain.ids.IdRegistry;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.ids.RoleKind;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class BuiltInIdsTest
{
	/** Roles with no known constant yet; the logging kills fill them. */
	private static final Set<Role> TO_CAPTURE = EnumSet.of(Role.JUDGE_FIRE_SURGE, Role.CRASH_FIREBALL, Role.SPEC_PURGING_STAFF, Role.SHADOW_POOL);

	private final IdRegistry ids = BuiltInIds.registry();

	@Test
	public void everyRoleHasAValueExceptTheOnesToCapture()
	{
		for (Role role : Role.values())
		{
			assertEquals(role.name(), !TO_CAPTURE.contains(role), ids.isCaptured(role));
		}
	}

	@Test
	public void recordingRolesMatchYamaUtilities()
	{
		assertEquals(Set.of(14176), ids.ids(Role.YAMA));
		assertEquals(Set.of(14180), ids.ids(Role.JUDGE));
		assertEquals(Set.of(14179), ids.ids(Role.VOID_FLARE));
		assertEquals(Set.of(6045), ids.ids(Role.YAMAS_DOMAIN));
		assertTrue(ids.ids(Role.VOICE_OF_YAMA).contains(14185));
	}

	@Test
	public void theSittingYamaIsKnownSoTheSelfCheckIgnoresHim()
	{
		assertEquals(Set.of(net.runelite.api.gameval.NpcID.YAMA_THRONE_OCCUPIED), ids.ids(Role.YAMA_SITTING));
	}

	@Test
	public void noIdBelongsToTwoRolesOfTheSameKind()
	{
		for (RoleKind kind : RoleKind.values())
		{
			Set<Integer> seen = new HashSet<>();
			for (Role role : Role.values())
			{
				if (role.kind() != kind)
				{
					continue;
				}
				for (int id : ids.ids(role))
				{
					assertTrue(role + " reuses " + id, seen.add(id));
				}
			}
		}
	}
}
