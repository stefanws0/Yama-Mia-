package com.yamareviewer.tools;

import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.ActorKind;
import com.yamareviewer.domain.event.AnimationObserved;
import com.yamareviewer.domain.event.DomainEvent;
import com.yamareviewer.domain.event.FightStarted;
import com.yamareviewer.domain.event.GraphicObserved;
import com.yamareviewer.domain.event.HitsplatObserved;
import com.yamareviewer.domain.event.NpcChangedObserved;
import com.yamareviewer.domain.event.NpcDespawnObserved;
import com.yamareviewer.domain.event.NpcSpawnObserved;
import com.yamareviewer.domain.event.OverheadTextObserved;
import com.yamareviewer.domain.event.PlayerLeft;
import com.yamareviewer.domain.event.PlayerSeen;
import com.yamareviewer.domain.event.ProjectileObserved;
import com.yamareviewer.domain.event.TickState;
import com.yamareviewer.domain.model.KillLog;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Development tool: makes a raw log fit to commit as a golden fixture. Player names become "Self",
 * "Partner" (the first other player seen) and "Other-n"; events about OTHER actors, which no projection
 * reads, are dropped. Everything else, header included, stays as recorded.
 */
public final class FixtureScrubber
{
	private FixtureScrubber()
	{
	}

	public static void main(String[] args) throws IOException
	{
		if (args.length != 2)
		{
			System.err.println("Usage: ./gradlew scrubFixture --args=\"<raw log .jsonl.gz> <fixture .jsonl.gz>\"");
			System.exit(1);
		}
		RawLogs.write(scrub(RawLogs.read(Path.of(args[0]))), Path.of(args[1]));
		System.out.println("Wrote " + args[1]);
	}

	public static KillLog scrub(KillLog log)
	{
		Map<String, String> aliases = new LinkedHashMap<>();
		List<DomainEvent> events = new ArrayList<>();
		for (DomainEvent event : log.getEvents())
		{
			if (event instanceof FightStarted)
			{
				FightStarted e = (FightStarted) event;
				events.add(new FightStarted(e.getTick(), "Self", e.getSelfPosition()));
			}
			else if (event instanceof PlayerSeen)
			{
				PlayerSeen e = (PlayerSeen) event;
				events.add(new PlayerSeen(e.getTick(), alias(aliases, e.getName())));
			}
			else if (event instanceof PlayerLeft)
			{
				PlayerLeft e = (PlayerLeft) event;
				events.add(new PlayerLeft(e.getTick(), alias(aliases, e.getName())));
			}
			else if (event instanceof TickState && isOther(((TickState) event).getYamaTarget()))
			{
				TickState e = (TickState) event;
				events.add(new TickState(e.getTick(), e.getPrayers(), e.getHitpoints(), e.getPrayerPoints(), e.getSpecEnergy(),
					e.getRunEnergy(), e.getWeaponId(), null, e.getSelfPosition(), e.getPartnerPosition()));
			}
			else if (!isOther(actorOf(event)))
			{
				events.add(event);
			}
		}
		return KillLog.of(log.getHeader(), events, log.getSkippedEvents());
	}

	private static String alias(Map<String, String> aliases, String name)
	{
		return aliases.computeIfAbsent(name, key -> aliases.isEmpty() ? "Partner" : "Other-" + aliases.size());
	}

	private static boolean isOther(Actor actor)
	{
		return actor != null && actor.getKind() == ActorKind.OTHER;
	}

	/** The actor an event is about, or null for events without one. */
	private static Actor actorOf(DomainEvent event)
	{
		if (event instanceof AnimationObserved)
		{
			return ((AnimationObserved) event).getActor();
		}
		if (event instanceof GraphicObserved)
		{
			return ((GraphicObserved) event).getActor();
		}
		if (event instanceof HitsplatObserved)
		{
			return ((HitsplatObserved) event).getTarget();
		}
		if (event instanceof OverheadTextObserved)
		{
			return ((OverheadTextObserved) event).getActor();
		}
		if (event instanceof NpcSpawnObserved)
		{
			return ((NpcSpawnObserved) event).getActor();
		}
		if (event instanceof NpcDespawnObserved)
		{
			return ((NpcDespawnObserved) event).getActor();
		}
		if (event instanceof NpcChangedObserved)
		{
			return ((NpcChangedObserved) event).getActor();
		}
		if (event instanceof ProjectileObserved)
		{
			return ((ProjectileObserved) event).getTarget();
		}
		return null;
	}
}
