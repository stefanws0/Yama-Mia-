package com.yamareviewer.adapter.recording;

import com.yamareviewer.application.command.KillSession;
import com.yamareviewer.domain.event.DomainEvent;
import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.event.EntryChoice;
import com.yamareviewer.domain.event.EntryChosen;
import com.yamareviewer.domain.event.InventoryDelta;
import com.yamareviewer.domain.event.TickState;
import com.yamareviewer.domain.ids.IdRegistry;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.model.KillLog;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.MenuEntry;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.NpcSpawned;
import net.runelite.api.events.PlayerSpawned;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.game.NpcUtil;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class GameEventListenerTest
{
	private final Client client = mock(Client.class);
	private final Player self = mock(Player.class);
	private final NPC yama = mock(NPC.class);
	private final PositionReader positions = mock(PositionReader.class);
	private final TickSampler sampler = mock(TickSampler.class);
	private final NpcUtil npcUtil = mock(NpcUtil.class);
	private final List<KillLog> ended = new ArrayList<>();
	private final List<Integer> unknownYamaIds = new ArrayList<>();
	private final IdRegistry ids = new IdRegistry(
		Map.of(Role.YAMA, Set.of(100), Role.YAMAS_DOMAIN, Set.of(6045), Role.VOICE_OF_YAMA, Set.of(200),
			Role.CONTRACT_ITEM_BLOODIED_BLOWS, Set.of(700), Role.YAMA_SITTING, Set.of(124)),
		Map.of());
	private KillSession session;
	private GameEventListener listener;

	@Before
	public void setUp()
	{
		when(client.getLocalPlayer()).thenReturn(self);
		when(self.getName()).thenReturn("Me");
		when(positions.regionId(any())).thenReturn(6045);
		when(yama.getId()).thenReturn(100);
		when(sampler.sample(anyInt(), any(), any())).thenAnswer(invocation ->
			new TickState(invocation.getArgument(0), Set.of(), 99, 99, 100, 100, -1, null, null, null));
		session = new KillSession(ended::add, (tick, kind) -> Optional.empty(), Clock.systemUTC(),
			() -> "kill", "0.1.0", "f00d", () -> false);
		ActorResolver actors = new ActorResolver(client, ids, session::partnerName);
		EventTranslator translator = new EventTranslator(ids, actors, session::currentTick, () -> false, itemId -> "item", positions);
		listener = new GameEventListener(client, ids, session, translator, sampler, positions, npcUtil,
			() -> CompletableFuture.completedFuture(null), unknownYamaIds::add);
	}

	private static NPC named(int id, String name)
	{
		NPC npc = mock(NPC.class);
		when(npc.getId()).thenReturn(id);
		when(npc.getName()).thenReturn(name);
		return npc;
	}

	@Test
	public void yamaSpawningInTheDomainStartsAFight()
	{
		listener.onNpcSpawned(new NpcSpawned(yama));

		assertTrue(session.isFighting());
	}

	@Test
	public void yamaOutsideTheDomainIsIgnored()
	{
		when(positions.regionId(any())).thenReturn(1234);

		listener.onNpcSpawned(new NpcSpawned(yama));

		assertFalse(session.isFighting());
	}

	@Test
	public void deathEndsOnTheNextTickKeepingThatTicksState()
	{
		listener.onNpcSpawned(new NpcSpawned(yama));
		listener.onGameTick(new GameTick());
		listener.onActorDeath(new ActorDeath(self));

		assertTrue(ended.isEmpty());
		listener.onGameTick(new GameTick());

		KillLog kill = ended.get(0);
		List<DomainEvent> events = kill.getEvents();
		assertEquals(EndReason.PLAYER_DIED, kill.endReason());
		assertTrue(events.get(events.size() - 2) instanceof TickState);
		assertEquals(1, events.get(events.size() - 2).getTick());
		assertEquals(1, kill.lastTick());
	}

	@Test
	public void yamaDespawningWhileDyingIsAKill()
	{
		listener.onNpcSpawned(new NpcSpawned(yama));
		when(npcUtil.isDying(yama)).thenReturn(true);
		listener.onNpcDespawned(new NpcDespawned(yama));
		listener.onGameTick(new GameTick());

		assertEquals(EndReason.YAMA_DIED, ended.get(0).endReason());
	}

	@Test
	public void yamaDespawningOnASceneReloadIsNotAKill()
	{
		listener.onNpcSpawned(new NpcSpawned(yama));
		listener.onNpcDespawned(new NpcDespawned(yama));
		listener.onGameTick(new GameTick());

		assertTrue(ended.isEmpty());
		assertTrue(session.isFighting());
	}

	@Test
	public void loggingOutEndsTheFightAsLeft()
	{
		listener.onNpcSpawned(new NpcSpawned(yama));
		GameStateChanged loggedOut = new GameStateChanged();
		loggedOut.setGameState(GameState.LOGIN_SCREEN);

		listener.onGameStateChanged(loggedOut);

		assertEquals(EndReason.LEFT, ended.get(0).endReason());
	}

	@Test
	public void travelAtTheVoiceIsRecordedWhenTheFightStarts()
	{
		NPC voice = mock(NPC.class);
		when(voice.getId()).thenReturn(200);
		MenuEntry entry = mock(MenuEntry.class);
		when(entry.getNpc()).thenReturn(voice);
		when(entry.getOption()).thenReturn("Travel");
		when(positions.regionId(any())).thenReturn(1234);
		listener.onMenuOptionClicked(new MenuOptionClicked(entry));

		when(positions.regionId(any())).thenReturn(6045);
		listener.onNpcSpawned(new NpcSpawned(yama));
		session.leave();

		assertEquals(EntryChoice.TRAVEL, ended.get(0).eventsOf(EntryChosen.class).get(0).getChoice());
	}

	@Test
	public void aPartnerLoadingInLateIsSeen()
	{
		listener.onNpcSpawned(new NpcSpawned(yama));
		Player buddy = mock(Player.class);
		when(buddy.getName()).thenReturn("Buddy");

		listener.onPlayerSpawned(new PlayerSpawned(buddy));

		assertEquals(Optional.of("Buddy"), session.partnerName());
	}

	@Test
	public void aContractConsumedAtTheChallengeIsRecordedAtFightStart()
	{
		listener.onGameTick(new GameTick());
		ItemContainer withContract = mock(ItemContainer.class);
		when(withContract.getItems()).thenReturn(new Item[]{new Item(700, 1)});
		listener.onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, withContract));

		listener.onNpcSpawned(new NpcSpawned(yama));
		session.leave();

		assertEquals(List.of(new InventoryDelta(0, 700, "item", -1)), ended.get(0).eventsOf(InventoryDelta.class));
	}

	@Test
	public void theLocalPlayerIsNeverAPartner()
	{
		listener.onPlayerSpawned(new PlayerSpawned(self));
		listener.onNpcSpawned(new NpcSpawned(yama));

		assertEquals(Optional.empty(), session.partnerName());
	}

	@Test
	public void anUnknownNpcNamedYamaWhileArmedIsReported()
	{
		listener.onGameTick(new GameTick());

		listener.onNpcSpawned(new NpcSpawned(named(999, "<col=ff0000>Yama</col>")));

		assertEquals(List.of(999), unknownYamaIds);
		assertFalse(session.isFighting());
	}

	@Test
	public void theSittingYamaIsNotReported()
	{
		listener.onGameTick(new GameTick());

		listener.onNpcSpawned(new NpcSpawned(named(124, "Yama")));

		assertTrue(unknownYamaIds.isEmpty());
	}

	@Test
	public void otherUnknownNpcsAreNotReported()
	{
		listener.onGameTick(new GameTick());

		listener.onNpcSpawned(new NpcSpawned(named(999, "Judge of Yama")));
		listener.onNpcSpawned(new NpcSpawned(named(998, null)));

		assertTrue(unknownYamaIds.isEmpty());
	}

	@Test
	public void anUnknownYamaOutsideTheDomainOrDuringAFightIsNotReported()
	{
		when(positions.regionId(any())).thenReturn(1234);
		listener.onNpcSpawned(new NpcSpawned(named(999, "Yama")));
		assertTrue(unknownYamaIds.isEmpty());

		when(positions.regionId(any())).thenReturn(6045);
		listener.onNpcSpawned(new NpcSpawned(yama));
		listener.onNpcSpawned(new NpcSpawned(named(999, "Yama")));
		assertTrue(unknownYamaIds.isEmpty());
	}

	@Test
	public void eachUnknownIdIsReportedOncePerLogin()
	{
		listener.onGameTick(new GameTick());
		listener.onNpcSpawned(new NpcSpawned(named(999, "Yama")));
		listener.onNpcSpawned(new NpcSpawned(named(999, "Yama")));
		assertEquals(List.of(999), unknownYamaIds);

		GameStateChanged loggedOut = new GameStateChanged();
		loggedOut.setGameState(GameState.LOGIN_SCREEN);
		listener.onGameStateChanged(loggedOut);
		listener.onGameTick(new GameTick());
		listener.onNpcSpawned(new NpcSpawned(named(999, "Yama")));

		assertEquals(List.of(999, 999), unknownYamaIds);
	}
}
