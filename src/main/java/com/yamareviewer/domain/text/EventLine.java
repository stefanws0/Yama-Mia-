package com.yamareviewer.domain.text;

import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.ActorKind;
import com.yamareviewer.domain.event.AnimationObserved;
import com.yamareviewer.domain.event.DomainEvent;
import com.yamareviewer.domain.event.EntryChosen;
import com.yamareviewer.domain.event.EventType;
import com.yamareviewer.domain.event.FightEnded;
import com.yamareviewer.domain.event.FightStarted;
import com.yamareviewer.domain.event.GameMessageObserved;
import com.yamareviewer.domain.event.GameStateObserved;
import com.yamareviewer.domain.event.GraphicObserved;
import com.yamareviewer.domain.event.GroundGraphicObserved;
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
import com.yamareviewer.domain.event.SuppliesSnapshot;
import com.yamareviewer.domain.event.TickState;
import com.yamareviewer.domain.event.VarbitObserved;
import com.yamareviewer.domain.event.WidgetTextObserved;
import java.util.TreeSet;

/** One event as one report line: "<tick> <type name> <fields>". Player names never appear (spec 2, rule 7). */
final class EventLine
{
	private EventLine()
	{
	}

	static String of(DomainEvent event, String partnerName)
	{
		return event.getTick() + " " + EventType.of(event).typeName() + " " + fields(event, partnerName);
	}

	private static String fields(DomainEvent event, String partnerName)
	{
		if (event instanceof EntryChosen)
		{
			return ((EntryChosen) event).getChoice().name();
		}
		if (event instanceof FightStarted)
		{
			return "at " + position(((FightStarted) event).getSelfPosition());
		}
		if (event instanceof FightEnded)
		{
			return ((FightEnded) event).getReason().name();
		}
		if (event instanceof PlayerSeen)
		{
			return player(((PlayerSeen) event).getName(), partnerName);
		}
		if (event instanceof PlayerLeft)
		{
			return player(((PlayerLeft) event).getName(), partnerName);
		}
		if (event instanceof GameStateObserved)
		{
			return ((GameStateObserved) event).getState().name();
		}
		if (event instanceof NpcSpawnObserved)
		{
			NpcSpawnObserved e = (NpcSpawnObserved) event;
			return actor(e.getActor()) + " id=" + e.getNpcId() + " index=" + e.getNpcIndex();
		}
		if (event instanceof NpcDespawnObserved)
		{
			NpcDespawnObserved e = (NpcDespawnObserved) event;
			return actor(e.getActor()) + " id=" + e.getNpcId() + " index=" + e.getNpcIndex() + " dying=" + e.isDying();
		}
		if (event instanceof NpcChangedObserved)
		{
			NpcChangedObserved e = (NpcChangedObserved) event;
			return actor(e.getActor()) + " " + e.getOldId() + " -> " + e.getNewId();
		}
		if (event instanceof ObjectSpawnObserved)
		{
			ObjectSpawnObserved e = (ObjectSpawnObserved) event;
			return "id=" + e.getObjectId() + " at " + position(e.getPosition());
		}
		if (event instanceof ObjectDespawnObserved)
		{
			ObjectDespawnObserved e = (ObjectDespawnObserved) event;
			return "id=" + e.getObjectId() + " at " + position(e.getPosition());
		}
		if (event instanceof ObjectAnimationObserved)
		{
			ObjectAnimationObserved e = (ObjectAnimationObserved) event;
			return "id=" + e.getObjectId() + " at " + position(e.getPosition()) + " animation=" + e.getAnimationId();
		}
		if (event instanceof OverheadTextObserved)
		{
			OverheadTextObserved e = (OverheadTextObserved) event;
			return actor(e.getActor()) + " \"" + e.getText() + "\"";
		}
		if (event instanceof GameMessageObserved)
		{
			return "\"" + ((GameMessageObserved) event).getText() + "\"";
		}
		if (event instanceof VarbitObserved)
		{
			VarbitObserved e = (VarbitObserved) event;
			return e.getVarbitId() + "=" + e.getValue();
		}
		if (event instanceof WidgetTextObserved)
		{
			WidgetTextObserved e = (WidgetTextObserved) event;
			return e.getComponentId() + " \"" + e.getText() + "\"";
		}
		if (event instanceof AnimationObserved)
		{
			AnimationObserved e = (AnimationObserved) event;
			return actor(e.getActor()) + " " + e.getAnimationId();
		}
		if (event instanceof GraphicObserved)
		{
			GraphicObserved e = (GraphicObserved) event;
			return actor(e.getActor()) + " " + e.getGraphicId();
		}
		if (event instanceof GroundGraphicObserved)
		{
			GroundGraphicObserved e = (GroundGraphicObserved) event;
			return e.getGraphicId() + " at " + position(e.getPosition());
		}
		if (event instanceof ProjectileObserved)
		{
			ProjectileObserved e = (ProjectileObserved) event;
			return e.getProjectileId() + " -> " + (e.getTarget() == null ? "tile" : actor(e.getTarget())) + " lands " + e.getEndTick();
		}
		if (event instanceof HitsplatObserved)
		{
			HitsplatObserved e = (HitsplatObserved) event;
			return actor(e.getTarget()) + " " + e.getKind() + " " + e.getAmount() + " type=" + e.getRawType() + " mine=" + e.isMine();
		}
		if (event instanceof TickState)
		{
			TickState e = (TickState) event;
			return "prayers=" + new TreeSet<>(e.getPrayers()) + " hp=" + e.getHitpoints() + " prayer=" + e.getPrayerPoints()
				+ " spec=" + e.getSpecEnergy() + " run=" + e.getRunEnergy() + " weapon=" + e.getWeaponId()
				+ " target=" + (e.getYamaTarget() == null ? "-" : actor(e.getYamaTarget()))
				+ " self=" + position(e.getSelfPosition()) + " partner=" + position(e.getPartnerPosition());
		}
		if (event instanceof InventoryDelta)
		{
			InventoryDelta e = (InventoryDelta) event;
			return e.getItemId() + " \"" + e.getName() + "\" " + e.getChange();
		}
		if (event instanceof SuppliesSnapshot)
		{
			SuppliesSnapshot e = (SuppliesSnapshot) event;
			return e.getKind() + " " + e.getItems().size() + " items";
		}
		return "-";
	}

	static String actor(Actor actor)
	{
		if (actor == null)
		{
			return "-";
		}
		switch (actor.getKind())
		{
			case FLARE:
				return "FLARE(" + actor.getRef() + ")";
			case NPC:
				return "NPC(" + actor.getRef() + ")";
			case OTHER:
				return "OTHER";
			default:
				return actor.getKind().name();
		}
	}

	private static String player(String name, String partnerName)
	{
		return name != null && name.equals(partnerName) ? ActorKind.PARTNER.name() : ActorKind.OTHER.name();
	}

	private static String position(Position position)
	{
		return position == null ? "-" : "(" + position.getX() + "," + position.getY() + "," + position.getPlane() + ")";
	}
}
