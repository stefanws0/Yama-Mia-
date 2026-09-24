package com.yamareviewer.domain.event;

import java.util.Optional;

/** The stable name written into logs for each event class. Never rename a released type name. */
public enum EventType
{
	ENTRY("entry", EntryChosen.class),
	FIGHT_START("fight-start", FightStarted.class),
	FIGHT_END("fight-end", FightEnded.class),
	PLAYER_SEEN("player-seen", PlayerSeen.class),
	PLAYER_LEFT("player-left", PlayerLeft.class),
	GAME_STATE("game-state", GameStateObserved.class),
	NPC_SPAWN("npc-spawn", NpcSpawnObserved.class),
	NPC_DESPAWN("npc-despawn", NpcDespawnObserved.class),
	NPC_CHANGED("npc-changed", NpcChangedObserved.class),
	OBJECT_SPAWN("object-spawn", ObjectSpawnObserved.class),
	OBJECT_DESPAWN("object-despawn", ObjectDespawnObserved.class),
	OBJECT_ANIMATION("object-animation", ObjectAnimationObserved.class),
	OVERHEAD("overhead", OverheadTextObserved.class),
	GAME_MESSAGE("game-message", GameMessageObserved.class),
	VARBIT("varbit", VarbitObserved.class),
	WIDGET_TEXT("widget-text", WidgetTextObserved.class),
	ANIMATION("animation", AnimationObserved.class),
	GRAPHIC("graphic", GraphicObserved.class),
	GROUND_GRAPHIC("ground-graphic", GroundGraphicObserved.class),
	PROJECTILE("projectile", ProjectileObserved.class),
	HITSPLAT("hitsplat", HitsplatObserved.class),
	TICK("tick", TickState.class),
	INVENTORY("inventory", InventoryDelta.class),
	SUPPLIES("supplies", SuppliesSnapshot.class);

	private final String typeName;
	private final Class<? extends DomainEvent> eventClass;

	EventType(String typeName, Class<? extends DomainEvent> eventClass)
	{
		this.typeName = typeName;
		this.eventClass = eventClass;
	}

	public String typeName()
	{
		return typeName;
	}

	public Class<? extends DomainEvent> eventClass()
	{
		return eventClass;
	}

	public static EventType of(DomainEvent event)
	{
		for (EventType type : values())
		{
			if (type.eventClass == event.getClass())
			{
				return type;
			}
		}
		throw new IllegalArgumentException("No event type for " + event.getClass().getName());
	}

	public static Optional<EventType> byName(String typeName)
	{
		for (EventType type : values())
		{
			if (type.typeName.equals(typeName))
			{
				return Optional.of(type);
			}
		}
		return Optional.empty();
	}
}
