package com.yamareviewer.adapter.persistence;

import com.google.gson.Gson;
import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.AnimationObserved;
import com.yamareviewer.domain.event.DomainEvent;
import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.event.EntryChoice;
import com.yamareviewer.domain.event.EntryChosen;
import com.yamareviewer.domain.event.EventType;
import com.yamareviewer.domain.event.FightEnded;
import com.yamareviewer.domain.event.FightStarted;
import com.yamareviewer.domain.event.GameMessageObserved;
import com.yamareviewer.domain.event.GameStateKind;
import com.yamareviewer.domain.event.GameStateObserved;
import com.yamareviewer.domain.event.GraphicObserved;
import com.yamareviewer.domain.event.GroundGraphicObserved;
import com.yamareviewer.domain.event.HitsplatKind;
import com.yamareviewer.domain.event.HitsplatObserved;
import com.yamareviewer.domain.event.InventoryDelta;
import com.yamareviewer.domain.event.NpcChangedObserved;
import com.yamareviewer.domain.event.NpcDespawnObserved;
import com.yamareviewer.domain.event.NpcSpawnObserved;
import com.yamareviewer.domain.event.ObjectAnimationObserved;
import com.yamareviewer.domain.event.ObjectDespawnObserved;
import com.yamareviewer.domain.event.ObjectSpawnObserved;
import com.yamareviewer.domain.event.OverheadTextObserved;
import com.yamareviewer.domain.event.PlayerLeft;
import com.yamareviewer.domain.event.PlayerSeen;
import com.yamareviewer.domain.event.Position;
import com.yamareviewer.domain.event.ProjectileObserved;
import com.yamareviewer.domain.event.ProtectionPrayer;
import com.yamareviewer.domain.event.SnapshotKind;
import com.yamareviewer.domain.event.SuppliesSnapshot;
import com.yamareviewer.domain.event.SupplyItem;
import com.yamareviewer.domain.event.TickState;
import com.yamareviewer.domain.event.VarbitObserved;
import com.yamareviewer.domain.event.WidgetTextObserved;
import com.yamareviewer.domain.model.KillHeader;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class EventCodecTest
{
	private final EventCodec codec = new EventCodec(new Gson());

	static List<DomainEvent> oneOfEach()
	{
		Position here = new Position(3200, 3200, 0);
		return List.of(
			new EntryChosen(0, EntryChoice.JOIN),
			new FightStarted(0, "Me", here),
			new FightEnded(90, EndReason.PLAYER_DIED),
			new PlayerSeen(0, "Buddy"),
			new PlayerLeft(80, "Buddy"),
			new GameStateObserved(3, GameStateKind.LOADING),
			new NpcSpawnObserved(1, Actor.flare(7), 1234, 7),
			new NpcDespawnObserved(2, Actor.YAMA, 1235, 1, true),
			new NpcChangedObserved(2, Actor.YAMA, 1235, 1236),
			new ObjectSpawnObserved(3, 555, here),
			new ObjectDespawnObserved(4, 555, here),
			new ObjectAnimationObserved(4, 555, here, 12169),
			new OverheadTextObserved(4, Actor.YAMA, "Enough."),
			new GameMessageObserved(4, "<col=ef1020>You've been injured and can't use protection prayers!</col>"),
			new VarbitObserved(5, 16550, 2),
			new WidgetTextObserved(5, 59899907, "Contract of Bloodied Blows"),
			new AnimationObserved(6, Actor.PARTNER, 42),
			new GraphicObserved(7, Actor.SELF, 43),
			new GroundGraphicObserved(8, 44, here),
			new ProjectileObserved(9, 45, Actor.SELF, 11),
			new HitsplatObserved(10, Actor.JUDGE, HitsplatKind.BLOCK, 0, 12, true),
			new TickState(11, Set.of(ProtectionPrayer.MAGIC), 80, 50, 100, 64, 4151, Actor.SELF, here, null),
			new InventoryDelta(12, 385, "Shark", -1),
			new SuppliesSnapshot(13, SnapshotKind.END, List.of(new SupplyItem(385, "Shark", 3, 800, 90))));
	}

	@Test
	public void roundTripsEveryEventType()
	{
		for (DomainEvent event : oneOfEach())
		{
			assertEquals(Optional.of(event), codec.decode(codec.encode(event)));
		}
	}

	@Test
	public void theRoundTripCoversEveryEventType()
	{
		Set<EventType> covered = oneOfEach().stream().map(EventType::of).collect(Collectors.toSet());
		assertEquals(Set.copyOf(Arrays.asList(EventType.values())), covered);
	}

	@Test
	public void usesTheStableTypeName()
	{
		assertTrue(codec.encode(new VarbitObserved(1, 2, 3)).startsWith("{\"type\":\"varbit\""));
	}

	@Test
	public void unknownTypeDecodesToEmpty()
	{
		assertEquals(Optional.empty(), codec.decode("{\"type\":\"from-the-future\",\"data\":{\"tick\":1}}"));
	}

	@Test
	public void headerRoundTrips()
	{
		KillHeader header = new KillHeader("k", 1L, 2L, "0.1.0", 1, "f00d", true);
		assertEquals(header, codec.decodeHeader(codec.encodeHeader(header)));
	}
}
