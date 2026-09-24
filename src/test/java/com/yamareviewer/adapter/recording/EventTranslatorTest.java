package com.yamareviewer.adapter.recording;

import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.AnimationObserved;
import com.yamareviewer.domain.event.DomainEvent;
import com.yamareviewer.domain.event.EntryChoice;
import com.yamareviewer.domain.event.GameMessageObserved;
import com.yamareviewer.domain.event.GameStateKind;
import com.yamareviewer.domain.event.GameStateObserved;
import com.yamareviewer.domain.event.GraphicObserved;
import com.yamareviewer.domain.event.HitsplatKind;
import com.yamareviewer.domain.event.HitsplatObserved;
import com.yamareviewer.domain.event.InventoryDelta;
import com.yamareviewer.domain.event.ObjectAnimationObserved;
import com.yamareviewer.domain.event.Position;
import com.yamareviewer.domain.event.ProjectileObserved;
import com.yamareviewer.domain.event.VarbitObserved;
import com.yamareviewer.domain.ids.IdRegistry;
import com.yamareviewer.domain.ids.Role;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.runelite.api.ActorSpotAnim;
import net.runelite.api.Animation;
import net.runelite.api.DynamicObject;
import net.runelite.api.GameObject;
import net.runelite.api.ChatMessageType;
import net.runelite.api.GameState;
import net.runelite.api.Hitsplat;
import net.runelite.api.HitsplatID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.IterableHashTable;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Projectile;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameObjectSpawned;
import net.runelite.api.events.GraphicChanged;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.ProjectileMoved;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.InventoryID;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class EventTranslatorTest
{
	private final IdRegistry ids = new IdRegistry(
		Map.of(Role.YAMA, Set.of(100), Role.PHASE_VARBIT, Set.of(16550), Role.GLYPH_FIRE, Set.of(500)),
		Map.of());
	private final ActorResolver actors = mock(ActorResolver.class);
	private final PositionReader positions = mock(PositionReader.class);
	private final NPC yama = mock(NPC.class);
	private final Player stranger = mock(Player.class);
	private boolean capture;
	private EventTranslator translator;

	@Before
	public void setUp()
	{
		when(actors.resolve(yama)).thenReturn(Actor.YAMA);
		when(actors.resolve(stranger)).thenReturn(Actor.other("Stranger"));
		when(positions.position(any())).thenReturn(new Position(1, 2, 0));
		translator = new EventTranslator(ids, actors, () -> 12, () -> capture, id -> "Item " + id, positions);
	}

	private static AnimationChanged animation(net.runelite.api.Actor actor)
	{
		AnimationChanged event = new AnimationChanged();
		event.setActor(actor);
		return event;
	}

	@Test
	public void animationOfAKnownActorIsRecorded()
	{
		when(yama.getAnimation()).thenReturn(4242);

		assertEquals(List.of(new AnimationObserved(12, Actor.YAMA, 4242)), translator.animationChanged(animation(yama)));
	}

	@Test
	public void idleAnimationIsSkipped()
	{
		when(yama.getAnimation()).thenReturn(-1);

		assertTrue(translator.animationChanged(animation(yama)).isEmpty());
	}

	@Test
	public void otherActorsAreDroppedUnlessCapturing()
	{
		when(stranger.getAnimation()).thenReturn(808);

		assertTrue(translator.animationChanged(animation(stranger)).isEmpty());
		capture = true;
		assertEquals(1, translator.animationChanged(animation(stranger)).size());
	}

	@Test
	public void hitsplatKeepsKindAmountAndOwnership()
	{
		Hitsplat hitsplat = mock(Hitsplat.class);
		when(hitsplat.getHitsplatType()).thenReturn(HitsplatID.BLOCK_ME);
		when(hitsplat.getAmount()).thenReturn(0);
		when(hitsplat.isMine()).thenReturn(true);
		HitsplatApplied event = new HitsplatApplied();
		event.setActor(yama);
		event.setHitsplat(hitsplat);

		assertEquals(List.of(new HitsplatObserved(12, Actor.YAMA, HitsplatKind.BLOCK, 0, HitsplatID.BLOCK_ME, true)),
			translator.hitsplatApplied(event));
	}

	@Test
	public void onlySystemMessagesAreRecorded()
	{
		ChatMessage system = new ChatMessage();
		system.setType(ChatMessageType.GAMEMESSAGE);
		system.setMessage("You absorb a Glyph of Fire.");
		ChatMessage chat = new ChatMessage();
		chat.setType(ChatMessageType.PUBLICCHAT);
		chat.setMessage("hello");

		assertEquals(List.of(new GameMessageObserved(12, "You absorb a Glyph of Fire.")), translator.chatMessage(system));
		assertTrue(translator.chatMessage(chat).isEmpty());
	}

	@Test
	public void onlyRoleVarbitsAreRecorded()
	{
		VarbitChanged phase = new VarbitChanged();
		phase.setVarbitId(16550);
		phase.setValue(2);
		VarbitChanged other = new VarbitChanged();
		other.setVarbitId(1);
		other.setValue(1);

		assertEquals(List.of(new VarbitObserved(12, 16550, 2)), translator.varbitChanged(phase));
		assertTrue(translator.varbitChanged(other).isEmpty());
	}

	@Test
	@SuppressWarnings("unchecked")
	public void eachSpotAnimIsRecordedOnce()
	{
		ActorSpotAnim spotAnim = mock(ActorSpotAnim.class);
		when(spotAnim.getId()).thenReturn(3247);
		when(spotAnim.getStartCycle()).thenReturn(500);
		IterableHashTable<ActorSpotAnim> table = mock(IterableHashTable.class);
		when(table.iterator()).thenAnswer(invocation -> List.of(spotAnim).iterator());
		when(yama.getSpotAnims()).thenReturn(table);
		GraphicChanged event = new GraphicChanged();
		event.setActor(yama);

		assertEquals(List.of(new GraphicObserved(12, Actor.YAMA, 3247)), translator.graphicChanged(event));
		assertTrue(translator.graphicChanged(event).isEmpty());
	}

	@Test
	public void projectileIsRecordedOnFirstSightingWithItsLandingTick()
	{
		Projectile projectile = mock(Projectile.class);
		when(projectile.getId()).thenReturn(77);
		when(projectile.getRemainingCycles()).thenReturn(45);
		when(projectile.getTargetActor()).thenReturn(yama);
		ProjectileMoved event = new ProjectileMoved();
		event.setProjectile(projectile);

		assertEquals(List.of(new ProjectileObserved(12, 77, Actor.YAMA, 14)), translator.projectileMoved(event));
		assertTrue(translator.projectileMoved(event).isEmpty());
	}

	@Test
	public void glyphAnimationsArePolledOncePerChange()
	{
		Animation activate = mock(Animation.class);
		when(activate.getId()).thenReturn(12169);
		DynamicObject renderable = mock(DynamicObject.class);
		when(renderable.getAnimation()).thenReturn(activate);
		GameObject glyph = mock(GameObject.class);
		when(glyph.getId()).thenReturn(500);
		when(glyph.getRenderable()).thenReturn(renderable);
		GameObjectSpawned spawned = new GameObjectSpawned();
		spawned.setGameObject(glyph);

		translator.gameObjectSpawned(spawned);

		assertEquals(List.of(new ObjectAnimationObserved(12, 500, new Position(1, 2, 0), 12169)), translator.pollObjectAnimations());
		assertTrue(translator.pollObjectAnimations().isEmpty());
	}

	@Test
	public void inventoryChangesBecomeDeltasAgainstTheBaseline()
	{
		translator.reset(Map.of(385, 5));
		ItemContainer inventory = mock(ItemContainer.class);
		when(inventory.getItems()).thenReturn(new Item[]{
			new Item(385, 1), new Item(385, 1), new Item(385, 1), new Item(-1, 0), new Item(3024, 1)});
		ItemContainerChanged event = new ItemContainerChanged(InventoryID.INV, inventory);

		List<DomainEvent> deltas = translator.itemContainerChanged(event);

		assertEquals(List.of(new InventoryDelta(12, 385, "Item 385", -2), new InventoryDelta(12, 3024, "Item 3024", 1)), deltas);
		assertTrue(translator.itemContainerChanged(event).isEmpty());
	}

	@Test
	public void gameStatesMapToTheDomain()
	{
		assertEquals(List.of(new GameStateObserved(12, GameStateKind.LOADING)), translator.gameState(GameState.LOADING));
		assertEquals(List.of(new GameStateObserved(12, GameStateKind.OTHER)), translator.gameState(GameState.STARTING));
	}

	@Test
	public void entryChoiceIgnoresColourTagsAndCase()
	{
		assertEquals(Optional.of(EntryChoice.TRAVEL), EventTranslator.entryChoice("Travel"));
		assertEquals(Optional.of(EntryChoice.JOIN), EventTranslator.entryChoice("<col=ff9040>join</col>"));
		assertEquals(Optional.empty(), EventTranslator.entryChoice("Talk-to"));
	}
}
