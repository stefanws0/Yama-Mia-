package com.yamareviewer.adapter.recording;

import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.ProtectionPrayer;
import com.yamareviewer.domain.event.TickState;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import static org.junit.Assert.assertEquals;
import org.junit.Test;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class TickSamplerTest
{
	@Test
	public void readsPrayersFromVarbitsAndEnergiesAsPercent()
	{
		Client client = mock(Client.class);
		Player self = mock(Player.class);
		NPC yama = mock(NPC.class);
		ActorResolver actors = mock(ActorResolver.class);
		when(client.getLocalPlayer()).thenReturn(self);
		when(yama.getInteracting()).thenReturn(self);
		when(actors.resolve(self)).thenReturn(Actor.SELF);
		when(client.getVarbitValue(VarbitID.PRAYER_PROTECTFROMMISSILES)).thenReturn(1);
		when(client.getBoostedSkillLevel(Skill.HITPOINTS)).thenReturn(87);
		when(client.getBoostedSkillLevel(Skill.PRAYER)).thenReturn(60);
		when(client.getVarpValue(VarPlayerID.SA_ENERGY)).thenReturn(750);
		when(client.getEnergy()).thenReturn(6400);

		TickState state = new TickSampler(client, actors, mock(PositionReader.class)).sample(9, yama, null);

		assertEquals(Set.of(ProtectionPrayer.MISSILES), state.getPrayers());
		assertEquals(87, state.getHitpoints());
		assertEquals(60, state.getPrayerPoints());
		assertEquals(75, state.getSpecEnergy());
		assertEquals(64, state.getRunEnergy());
		assertEquals(-1, state.getWeaponId());
		assertEquals(Actor.SELF, state.getYamaTarget());
	}
}
