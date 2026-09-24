package com.yamareviewer.domain.event;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class ActorTest
{
	@Test
	public void playersAreSelfAndPartnerOnly()
	{
		assertTrue(Actor.SELF.isPlayer());
		assertTrue(Actor.PARTNER.isPlayer());
		assertFalse(Actor.YAMA.isPlayer());
		assertFalse(Actor.other("Stranger").isPlayer());
	}

	@Test
	public void flaresAreIdentifiedByNpcIndex()
	{
		assertEquals(Actor.flare(7), Actor.flare(7));
		assertEquals(ActorKind.FLARE, Actor.flare(7).getKind());
		assertEquals(7, Actor.flare(7).getRef());
	}

	@Test
	public void roleNpcsCarryTheirNpcId()
	{
		assertEquals(ActorKind.NPC, Actor.npc(14182).getKind());
		assertEquals(14182, Actor.npc(14182).getRef());
	}
}
