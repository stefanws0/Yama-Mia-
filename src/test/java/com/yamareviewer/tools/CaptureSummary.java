package com.yamareviewer.tools;

import com.google.gson.Gson;
import com.yamareviewer.adapter.persistence.EventCodec;
import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.AnimationObserved;
import com.yamareviewer.domain.event.DomainEvent;
import com.yamareviewer.domain.event.GameMessageObserved;
import com.yamareviewer.domain.event.GraphicObserved;
import com.yamareviewer.domain.event.GroundGraphicObserved;
import com.yamareviewer.domain.event.HitsplatObserved;
import com.yamareviewer.domain.event.NpcSpawnObserved;
import com.yamareviewer.domain.event.ObjectAnimationObserved;
import com.yamareviewer.domain.event.ObjectSpawnObserved;
import com.yamareviewer.domain.event.OverheadTextObserved;
import com.yamareviewer.domain.event.ProjectileObserved;
import com.yamareviewer.domain.event.VarbitObserved;
import com.yamareviewer.domain.event.WidgetTextObserved;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.BiFunction;
import java.util.zip.GZIPInputStream;

/** Development tool: which IDs appeared in a raw log, how often, at what rhythm, and their gameval names. */
public final class CaptureSummary
{
	static final String ROW = "%-16s %-18s %9d %6d %6d %6d %5s  %s%n";

	public static void main(String[] args) throws IOException
	{
		if (args.length != 1)
		{
			System.err.println("Usage: ./gradlew captureSummary --args=\"<path to raw/*.jsonl.gz>\"");
			System.exit(1);
		}
		String text;
		try (InputStream in = new GZIPInputStream(Files.newInputStream(Path.of(args[0]))))
		{
			text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		EventCodec codec = new EventCodec(new Gson());
		String[] lines = text.split("\n");
		List<DomainEvent> events = new ArrayList<>();
		for (int i = 1; i < lines.length; i++)
		{
			if (!lines[i].isBlank())
			{
				codec.decode(lines[i]).ifPresent(events::add);
			}
		}
		GamevalNames names = new GamevalNames();
		System.out.print(summarize(events, names::name));
	}

	static String summarize(List<DomainEvent> events, BiFunction<String, Integer, Optional<String>> names)
	{
		Map<String, Row> rows = new TreeMap<>();
		for (DomainEvent event : events)
		{
			Row key = keyOf(event);
			if (key != null)
			{
				rows.computeIfAbsent(key.sortKey(), k -> key).ticks.add(event.getTick());
			}
		}
		StringBuilder out = new StringBuilder(String.format("%-16s %-18s %9s %6s %6s %6s %5s  %s%n",
			"event", "actor", "id", "count", "first", "last", "gap", "gameval"));
		for (Row row : rows.values())
		{
			List<Integer> ticks = row.ticks;
			out.append(String.format(ROW, row.kind, row.actor, row.id, ticks.size(), ticks.get(0),
				ticks.get(ticks.size() - 1), mostCommonGap(ticks), names.apply(row.kind, row.id).orElse("")));
		}
		return out.toString();
	}

	private static String mostCommonGap(List<Integer> ticks)
	{
		Map<Integer, Integer> gaps = new HashMap<>();
		for (int i = 1; i < ticks.size(); i++)
		{
			gaps.merge(ticks.get(i) - ticks.get(i - 1), 1, Integer::sum);
		}
		return gaps.entrySet().stream()
			.max(Map.Entry.<Integer, Integer>comparingByValue().thenComparing(Map.Entry.comparingByKey()))
			.map(entry -> String.valueOf(entry.getKey()))
			.orElse("-");
	}

	private static Row keyOf(DomainEvent event)
	{
		if (event instanceof AnimationObserved)
		{
			AnimationObserved e = (AnimationObserved) event;
			return new Row("animation", label(e.getActor()), e.getAnimationId());
		}
		if (event instanceof GraphicObserved)
		{
			GraphicObserved e = (GraphicObserved) event;
			return new Row("graphic", label(e.getActor()), e.getGraphicId());
		}
		if (event instanceof GroundGraphicObserved)
		{
			return new Row("ground-graphic", "-", ((GroundGraphicObserved) event).getGraphicId());
		}
		if (event instanceof ProjectileObserved)
		{
			ProjectileObserved e = (ProjectileObserved) event;
			return new Row("projectile", "-> " + label(e.getTarget()), e.getProjectileId());
		}
		if (event instanceof NpcSpawnObserved)
		{
			NpcSpawnObserved e = (NpcSpawnObserved) event;
			return new Row("npc", label(e.getActor()), e.getNpcId());
		}
		if (event instanceof ObjectSpawnObserved)
		{
			return new Row("object", "-", ((ObjectSpawnObserved) event).getObjectId());
		}
		if (event instanceof ObjectAnimationObserved)
		{
			ObjectAnimationObserved e = (ObjectAnimationObserved) event;
			return new Row("object-animation", "object " + e.getObjectId(), e.getAnimationId());
		}
		if (event instanceof VarbitObserved)
		{
			VarbitObserved e = (VarbitObserved) event;
			return new Row("varbit", "value " + e.getValue(), e.getVarbitId());
		}
		if (event instanceof OverheadTextObserved)
		{
			OverheadTextObserved e = (OverheadTextObserved) event;
			return new Row("overhead", label(e.getActor()) + " " + e.getText(), 0);
		}
		if (event instanceof GameMessageObserved)
		{
			return new Row("game-message", ((GameMessageObserved) event).getText(), 0);
		}
		if (event instanceof WidgetTextObserved)
		{
			WidgetTextObserved e = (WidgetTextObserved) event;
			return new Row("widget-text", e.getText(), e.getComponentId());
		}
		if (event instanceof HitsplatObserved)
		{
			HitsplatObserved e = (HitsplatObserved) event;
			return new Row("hitsplat", label(e.getTarget()), e.getRawType());
		}
		return null;
	}

	private static String label(Actor actor)
	{
		if (actor == null)
		{
			return "tile";
		}
		if (actor.getName() != null)
		{
			return actor.getKind() + "(" + actor.getName() + ")";
		}
		return actor.getRef() >= 0 ? actor.getKind() + "(" + actor.getRef() + ")" : actor.getKind().toString();
	}

	private static final class Row
	{
		private final String kind;
		private final String actor;
		private final int id;
		private final List<Integer> ticks = new ArrayList<>();

		private Row(String kind, String actor, int id)
		{
			this.kind = kind;
			this.actor = actor;
			this.id = id;
		}

		private String sortKey()
		{
			return kind + "|" + actor + "|" + String.format("%09d", id);
		}
	}
}
