package com.yamareviewer.adapter.recording;

import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.ActorKind;
import com.yamareviewer.domain.event.AnimationObserved;
import com.yamareviewer.domain.event.DomainEvent;
import com.yamareviewer.domain.event.EntryChoice;
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
import com.yamareviewer.domain.event.ProjectileObserved;
import com.yamareviewer.domain.event.VarbitObserved;
import com.yamareviewer.domain.event.WidgetTextObserved;
import com.yamareviewer.domain.ids.IdRegistry;
import com.yamareviewer.domain.ids.RoleKind;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;
import java.util.function.IntFunction;
import java.util.function.IntSupplier;
import net.runelite.api.ActorSpotAnim;
import net.runelite.api.Animation;
import net.runelite.api.DynamicObject;
import net.runelite.api.GameObject;
import net.runelite.api.ChatMessageType;
import net.runelite.api.GameState;
import net.runelite.api.GraphicsObject;
import net.runelite.api.Hitsplat;
import net.runelite.api.HitsplatID;
import net.runelite.api.Item;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Projectile;
import net.runelite.api.Renderable;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameObjectDespawned;
import net.runelite.api.events.GameObjectSpawned;
import net.runelite.api.events.GraphicChanged;
import net.runelite.api.events.GraphicsObjectCreated;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.NpcChanged;
import net.runelite.api.events.OverheadTextChanged;
import net.runelite.api.events.ProjectileMoved;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.util.Text;

/**
 * The anti-corruption layer: turns RuneLite events into raw domain observations. It never classifies.
 * Events about unrelated actors are dropped unless capture mode is on; ground graphics, projectiles and
 * objects are kept unfiltered because everything of that kind in the arena belongs to the fight.
 * Client thread only.
 */
public final class EventTranslator
{
	private static final int CLIENT_CYCLES_PER_TICK = 30;

	private final IdRegistry ids;
	private final ActorResolver actors;
	private final IntSupplier tick;
	private final BooleanSupplier capture;
	private final IntFunction<String> itemNames;
	private final PositionReader positions;

	private final Set<String> seenSpotAnims = new HashSet<>();
	private final Set<Projectile> seenProjectiles = Collections.newSetFromMap(new IdentityHashMap<>());
	private final Map<GameObject, Integer> trackedObjects = new IdentityHashMap<>();
	private Map<Integer, Integer> inventory = Map.of();

	public EventTranslator(IdRegistry ids, ActorResolver actors, IntSupplier tick, BooleanSupplier capture,
		IntFunction<String> itemNames, PositionReader positions)
	{
		this.ids = ids;
		this.actors = actors;
		this.tick = tick;
		this.capture = capture;
		this.itemNames = itemNames;
		this.positions = positions;
	}

	/** Called when a fight starts: forget the previous fight and set the inventory baseline. */
	public void reset(Map<Integer, Integer> inventoryNow)
	{
		seenSpotAnims.clear();
		seenProjectiles.clear();
		inventory = Map.copyOf(inventoryNow);
	}

	public List<DomainEvent> animationChanged(AnimationChanged event)
	{
		int animationId = event.getActor().getAnimation();
		if (animationId == -1)
		{
			return List.of();
		}
		Actor actor = actors.resolve(event.getActor());
		return keep(actor) ? List.of(new AnimationObserved(tick.getAsInt(), actor, animationId)) : List.of();
	}

	public List<DomainEvent> graphicChanged(GraphicChanged event)
	{
		Actor actor = actors.resolve(event.getActor());
		if (!keep(actor))
		{
			return List.of();
		}
		List<DomainEvent> result = new ArrayList<>();
		for (ActorSpotAnim spotAnim : event.getActor().getSpotAnims())
		{
			String key = actor + "/" + spotAnim.getId() + "/" + spotAnim.getStartCycle();
			if (seenSpotAnims.add(key))
			{
				result.add(new GraphicObserved(tick.getAsInt(), actor, spotAnim.getId()));
			}
		}
		return result;
	}

	public List<DomainEvent> graphicsObjectCreated(GraphicsObjectCreated event)
	{
		GraphicsObject graphic = event.getGraphicsObject();
		return List.of(new GroundGraphicObserved(tick.getAsInt(), graphic.getId(), positions.position(graphic.getLocation())));
	}

	public List<DomainEvent> projectileMoved(ProjectileMoved event)
	{
		Projectile projectile = event.getProjectile();
		if (!seenProjectiles.add(projectile))
		{
			return List.of();
		}
		net.runelite.api.Actor target = projectile.getTargetActor();
		int ticksToLand = (projectile.getRemainingCycles() + CLIENT_CYCLES_PER_TICK - 1) / CLIENT_CYCLES_PER_TICK;
		return List.of(new ProjectileObserved(tick.getAsInt(), projectile.getId(),
			target == null ? null : actors.resolve(target), tick.getAsInt() + ticksToLand));
	}

	public List<DomainEvent> hitsplatApplied(HitsplatApplied event)
	{
		Actor target = actors.resolve(event.getActor());
		if (!keep(target))
		{
			return List.of();
		}
		Hitsplat hitsplat = event.getHitsplat();
		return List.of(new HitsplatObserved(tick.getAsInt(), target, kindOf(hitsplat), hitsplat.getAmount(),
			hitsplat.getHitsplatType(), hitsplat.isMine()));
	}

	/** Only NPC overheads; a player's overhead text is their public chat and is never recorded, not even in capture mode. */
	public List<DomainEvent> overheadTextChanged(OverheadTextChanged event)
	{
		if (event.getActor() instanceof Player)
		{
			return List.of();
		}
		Actor actor = actors.resolve(event.getActor());
		return keep(actor) ? List.of(new OverheadTextObserved(tick.getAsInt(), actor, event.getOverheadText())) : List.of();
	}

	/** Only system messages; player chat is never recorded. */
	public List<DomainEvent> chatMessage(ChatMessage event)
	{
		return event.getType() == ChatMessageType.GAMEMESSAGE
			? List.of(new GameMessageObserved(tick.getAsInt(), event.getMessage()))
			: List.of();
	}

	/** VarbitChanged fires for every varbit and varp, so only varbits with a role are kept. */
	public List<DomainEvent> varbitChanged(VarbitChanged event)
	{
		return ids.roleOf(RoleKind.VARBIT, event.getVarbitId()).isPresent()
			? List.of(new VarbitObserved(tick.getAsInt(), event.getVarbitId(), event.getValue()))
			: List.of();
	}

	public List<DomainEvent> npcSpawned(NPC npc)
	{
		Actor actor = actors.resolve(npc);
		return keep(actor) ? List.of(new NpcSpawnObserved(tick.getAsInt(), actor, npc.getId(), npc.getIndex())) : List.of();
	}

	public List<DomainEvent> npcDespawned(NPC npc, boolean dying)
	{
		Actor actor = actors.resolve(npc);
		return keep(actor) ? List.of(new NpcDespawnObserved(tick.getAsInt(), actor, npc.getId(), npc.getIndex(), dying)) : List.of();
	}

	public List<DomainEvent> npcChanged(NpcChanged event)
	{
		NPC npc = event.getNpc();
		Actor actor = actors.resolve(npc);
		return keep(actor) ? List.of(new NpcChangedObserved(tick.getAsInt(), actor, event.getOld().getId(), npc.getId())) : List.of();
	}

	/**
	 * Also starts tracking glyph objects (every object in capture mode) for animation polling, but only objects
	 * in the arena; inArena is asked only for an object that would be tracked.
	 */
	public List<DomainEvent> gameObjectSpawned(GameObjectSpawned event, BooleanSupplier inArena)
	{
		GameObject object = event.getGameObject();
		if ((capture.getAsBoolean() || ids.roleOf(RoleKind.OBJECT, object.getId()).isPresent()) && inArena.getAsBoolean())
		{
			trackedObjects.put(object, -1);
		}
		return List.of(new ObjectSpawnObserved(tick.getAsInt(), object.getId(), positions.position(object.getLocalLocation())));
	}

	public List<DomainEvent> gameObjectDespawned(GameObjectDespawned event)
	{
		GameObject object = event.getGameObject();
		trackedObjects.remove(object);
		return List.of(new ObjectDespawnObserved(tick.getAsInt(), object.getId(), positions.position(object.getLocalLocation())));
	}

	/** Stops polling every tracked object: the scene is reloading or the player left the arena. */
	public void forgetObjects()
	{
		trackedObjects.clear();
	}

	/** RuneLite has no event for object animations, so tracked objects are polled once per tick. */
	public List<DomainEvent> pollObjectAnimations()
	{
		List<DomainEvent> result = new ArrayList<>();
		for (Map.Entry<GameObject, Integer> entry : trackedObjects.entrySet())
		{
			GameObject object = entry.getKey();
			int animationId = animationOf(object);
			if (animationId != entry.getValue())
			{
				entry.setValue(animationId);
				if (animationId != -1)
				{
					result.add(new ObjectAnimationObserved(tick.getAsInt(), object.getId(),
						positions.position(object.getLocalLocation()), animationId));
				}
			}
		}
		return result;
	}

	public List<DomainEvent> itemContainerChanged(ItemContainerChanged event)
	{
		if (event.getContainerId() != InventoryID.INV)
		{
			return List.of();
		}
		Map<Integer, Integer> now = aggregate(event.getItemContainer().getItems());
		Set<Integer> itemIds = new TreeSet<>(inventory.keySet());
		itemIds.addAll(now.keySet());
		List<DomainEvent> result = new ArrayList<>();
		for (int itemId : itemIds)
		{
			int change = now.getOrDefault(itemId, 0) - inventory.getOrDefault(itemId, 0);
			if (change != 0)
			{
				result.add(new InventoryDelta(tick.getAsInt(), itemId, itemNames.apply(itemId), change));
			}
		}
		inventory = now;
		return result;
	}

	public List<DomainEvent> widgetText(int componentId, String text)
	{
		return List.of(new WidgetTextObserved(tick.getAsInt(), componentId, text));
	}

	public List<DomainEvent> gameState(GameState state)
	{
		return List.of(new GameStateObserved(tick.getAsInt(), kindOf(state)));
	}

	/** A contract item consumed at the challenge, just before the fight started (spec 6.3). */
	public DomainEvent contractConsumed(int itemId)
	{
		return new InventoryDelta(tick.getAsInt(), itemId, itemNames.apply(itemId), -1);
	}

	/** Quantities per item id, ignoring empty slots. */
	public static Map<Integer, Integer> aggregate(Item[] items)
	{
		Map<Integer, Integer> quantities = new TreeMap<>();
		for (Item item : items)
		{
			if (item.getId() >= 0 && item.getQuantity() > 0)
			{
				quantities.merge(item.getId(), item.getQuantity(), Integer::sum);
			}
		}
		return quantities;
	}

	public static Optional<EntryChoice> entryChoice(String menuOption)
	{
		String option = Text.removeTags(menuOption).trim();
		if (option.equalsIgnoreCase("Travel"))
		{
			return Optional.of(EntryChoice.TRAVEL);
		}
		if (option.equalsIgnoreCase("Join"))
		{
			return Optional.of(EntryChoice.JOIN);
		}
		return Optional.empty();
	}

	static HitsplatKind kindOf(Hitsplat hitsplat)
	{
		int type = hitsplat.getHitsplatType();
		if (type == HitsplatID.HEAL)
		{
			return HitsplatKind.HEAL;
		}
		if (type == HitsplatID.BLOCK_ME || type == HitsplatID.BLOCK_OTHER)
		{
			return HitsplatKind.BLOCK;
		}
		return hitsplat.getAmount() > 0 ? HitsplatKind.DAMAGE : HitsplatKind.OTHER;
	}

	static GameStateKind kindOf(GameState state)
	{
		switch (state)
		{
			case LOADING:
				return GameStateKind.LOADING;
			case LOGGED_IN:
				return GameStateKind.LOGGED_IN;
			case HOPPING:
				return GameStateKind.HOPPING;
			case LOGIN_SCREEN:
				return GameStateKind.LOGIN_SCREEN;
			case CONNECTION_LOST:
				return GameStateKind.CONNECTION_LOST;
			default:
				return GameStateKind.OTHER;
		}
	}

	private static int animationOf(GameObject object)
	{
		Renderable renderable = object.getRenderable();
		if (renderable instanceof DynamicObject)
		{
			Animation animation = ((DynamicObject) renderable).getAnimation();
			return animation == null ? -1 : animation.getId();
		}
		return -1;
	}

	private boolean keep(Actor actor)
	{
		return capture.getAsBoolean() || actor.getKind() != ActorKind.OTHER;
	}
}
