package com.yamareviewer.adapter.recording;

import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.ids.IdRegistry;
import com.yamareviewer.domain.ids.Role;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import static org.junit.Assert.assertEquals;
import org.junit.Before;
import org.junit.Test;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ActorResolverTest
{
	private final IdRegistry ids = new IdRegistry(
		Map.of(Role.YAMA, Set.of(100), Role.JUDGE, Set.of(101), Role.VOID_FLARE, Set.of(102), Role.METEOR_NPC, Set.of(103)),
		Map.of());
	private final Client client = mock(Client.class);
	private final Player self = mock(Player.class);
	private ActorResolver resolver;

	@Before
	public void setUp()
	{
		when(client.getLocalPlayer()).thenReturn(self);
		resolver = new ActorResolver(client, ids, () -> Optional.of("Buddy"));
	}

	private static NPC npc(int id, int index)
	{
		NPC npc = mock(NPC.class);
		when(npc.getId()).thenReturn(id);
		when(npc.getIndex()).thenReturn(index);
		return npc;
	}

	private static Player player(String name)
	{
		Player player = mock(Player.class);
		when(player.getName()).thenReturn(name);
		return player;
	}

	@Test
	public void resolvesPlayers()
	{
		assertEquals(Actor.SELF, resolver.resolve(self));
		assertEquals(Actor.PARTNER, resolver.resolve(player("Buddy")));
		assertEquals(Actor.other("Stranger"), resolver.resolve(player("Stranger")));
	}

	@Test
	public void resolvesNpcsByRole()
	{
		assertEquals(Actor.YAMA, resolver.resolve(npc(100, 1)));
		assertEquals(Actor.JUDGE, resolver.resolve(npc(101, 2)));
		assertEquals(Actor.flare(33), resolver.resolve(npc(102, 33)));
		assertEquals(Actor.npc(103), resolver.resolve(npc(103, 4)));
		assertEquals(Actor.other("npc 999"), resolver.resolve(npc(999, 5)));
	}

	@Test
	public void nullIsNobody()
	{
		assertEquals(Actor.other("none"), resolver.resolve(null));
	}
}
