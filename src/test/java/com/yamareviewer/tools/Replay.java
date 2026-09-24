package com.yamareviewer.tools;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.yamareviewer.adapter.ids.BuiltInIds;
import com.yamareviewer.adapter.persistence.EventCodec;
import com.yamareviewer.domain.event.DomainEvent;
import com.yamareviewer.domain.ids.IdRegistry;
import com.yamareviewer.domain.ids.Rules;
import com.yamareviewer.domain.model.KillHeader;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.projection.KillReviewAssembler;
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
 * Development tool: reviews a raw log with the built-in IDs and default rules and prints the review as
 * text, or as JSON with --json (the expected-review fixtures of Part 3's golden tests are made this way).
 */
public final class Replay
{
	private Replay()
	{
	}

	public static void main(String[] args) throws IOException
	{
		if (args.length < 1 || args.length > 2 || (args.length == 2 && !args[1].equals("--json")))
		{
			System.err.println("Usage: ./gradlew replay --args=\"<path to raw/*.jsonl.gz> [--json]\"");
			System.exit(1);
		}
		KillReview review = review(read(Path.of(args[0])), BuiltInIds.registry());
		if (args.length == 2)
		{
			System.out.println(new GsonBuilder().setPrettyPrinting().create().toJson(review));
		}
		else
		{
			System.out.print(text(ReviewFormatter.format(review)));
		}
	}

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

	public static KillReview review(KillLog log, IdRegistry ids)
	{
		ReviewBuilder builder = new ReviewBuilder(Projections.standard(), ids, Rules.DEFAULT);
		return KillReviewAssembler.assemble(log, builder.run(log, ReviewSettings.DEFAULT));
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
