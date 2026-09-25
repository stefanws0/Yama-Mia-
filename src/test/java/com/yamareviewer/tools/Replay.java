package com.yamareviewer.tools;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.yamareviewer.adapter.ids.BuiltInIds;
import com.yamareviewer.adapter.persistence.EventCodec;
import com.yamareviewer.domain.event.DomainEvent;
import com.yamareviewer.domain.health.CheckOutcome;
import com.yamareviewer.domain.health.HealthCheckRunner;
import com.yamareviewer.domain.health.HealthChecks;
import com.yamareviewer.domain.ids.IdRegistry;
import com.yamareviewer.domain.ids.Rules;
import com.yamareviewer.domain.model.KillHeader;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.projection.KillReviewAssembler;
import com.yamareviewer.domain.projection.ProjectionContext;
import com.yamareviewer.domain.projection.Projections;
import com.yamareviewer.domain.projection.ReviewBuilder;
import com.yamareviewer.domain.projection.ReviewSettings;
import com.yamareviewer.domain.review.KillReview;
import com.yamareviewer.domain.text.ReviewFormatter;
import com.yamareviewer.domain.text.ReviewView;
import com.yamareviewer.domain.text.ViewSection;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.zip.GZIPInputStream;

/**
 * Development tool: prints the review of a raw log with the built-in IDs and default rules, and with a
 * second argument writes the review JSON that the golden test compares against. Keeps Part 2's
 * read(Path), review(KillLog, IdRegistry) and text(ReviewView), which Part 2's ReplayTest uses.
 */
public final class Replay
{
	public static final Gson GSON = new GsonBuilder().serializeNulls().setPrettyPrinting().create();

	private Replay()
	{
	}

	public static void main(String[] args) throws IOException
	{
		if (args.length < 1 || args.length > 2)
		{
			System.err.println("Usage: ./gradlew replay --args=\"<raw log .jsonl.gz> [<expected .review.json>]\"");
			System.exit(1);
		}
		KillReview review = review(read(Path.of(args[0])));
		System.out.print(text(ReviewFormatter.format(review)));
		System.out.println();
		System.out.println("== Health checks");
		for (CheckOutcome outcome : checks(read(Path.of(args[0]))))
		{
			System.out.println("  " + outcome.name() + ": " + outcome.getResult());
		}
		if (args.length == 2)
		{
			Files.write(Path.of(args[1]), json(review).getBytes(StandardCharsets.UTF_8));
			System.out.println("Wrote " + args[1]);
		}
	}

	/** Part 2's reader, unchanged: the header line, then one event per line; unknown events are counted as skipped. */
	public static KillLog read(Path file) throws IOException
	{
		String text;
		try (InputStream in = new GZIPInputStream(Files.newInputStream(file)))
		{
			text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		EventCodec codec = new EventCodec(new Gson());
		String[] lines = text.split("\n");
		KillHeader header = codec.decodeHeader(lines[0]);
		List<DomainEvent> events = new ArrayList<>();
		int skipped = 0;
		for (int i = 1; i < lines.length; i++)
		{
			if (lines[i].isBlank())
			{
				continue;
			}
			Optional<DomainEvent> event = codec.decode(lines[i]);
			if (event.isPresent())
			{
				events.add(event.get());
			}
			else
			{
				skipped++;
			}
		}
		return KillLog.of(header, events, skipped);
	}

	/** Exactly what the plugin computes for this log with the built-in IDs, without health checks (Part 4 adds those). */
	public static KillReview review(KillLog log)
	{
		return review(log, BuiltInIds.registry());
	}

	public static KillReview review(KillLog log, IdRegistry ids)
	{
		ReviewBuilder builder = new ReviewBuilder(Projections.standard(), ids, Rules.DEFAULT);
		ProjectionContext context = builder.run(log, ReviewSettings.DEFAULT);
		return KillReviewAssembler.assemble(log, context);
	}

	/** The health checks on the context the plugin would use; the review of review(log) is left as it is. */
	public static List<CheckOutcome> checks(KillLog log)
	{
		ReviewBuilder builder = new ReviewBuilder(Projections.standard(), BuiltInIds.registry(), Rules.DEFAULT);
		return HealthCheckRunner.apply(log, builder.run(log, ReviewSettings.DEFAULT), HealthChecks.standard());
	}

	public static String json(KillReview review)
	{
		return GSON.toJson(review);
	}

	public static String text(ReviewView view)
	{
		StringBuilder out = new StringBuilder(view.getHeadline()).append('\n').append(view.getStatus()).append('\n');
		for (ViewSection section : view.getSections())
		{
			out.append('\n').append(section.getTitle()).append('\n');
			if (section.getHiddenReason() != null)
			{
				out.append("  hidden: ").append(section.getHiddenReason()).append('\n');
			}
			for (String line : section.getLines())
			{
				out.append("  ").append(line).append('\n');
			}
		}
		return out.toString();
	}
}
