package com.yamareviewer.adapter.persistence;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.yamareviewer.application.port.ReviewRepository;
import com.yamareviewer.domain.history.HistoryKey;
import com.yamareviewer.domain.model.Mode;
import com.yamareviewer.domain.review.KillReview;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;

/** reviews/<MODE>/<startEpochMs, 13 digits>-<killId>.json, one KillReview per file (spec 7.5). */
@Slf4j
public final class GsonReviewRepository implements ReviewRepository
{
	private static final String REVIEWS = "reviews";
	private static final String SUFFIX = ".json";

	private final FileStore files;
	private final Gson gson;

	public GsonReviewRepository(FileStore files, Gson gson)
	{
		this.files = files;
		this.gson = gson;
	}

	@Override
	public void save(KillReview review) throws IOException
	{
		files.write(path(review), gson.toJson(review).getBytes(StandardCharsets.UTF_8));
	}

	@Override
	public List<KillReview> loadAll() throws IOException
	{
		List<Stored> stored = new ArrayList<>();
		for (Mode mode : Mode.values())
		{
			String dir = REVIEWS + "/" + mode.name();
			for (String name : files.list(dir))
			{
				if (!name.endsWith(SUFFIX))
				{
					continue;
				}
				String path = dir + "/" + name;
				try
				{
					parse(new String(files.read(path), StandardCharsets.UTF_8)).ifPresent(review -> stored.add(new Stored(name, review)));
				}
				catch (IOException | RuntimeException e)
				{
					log.warn("Unreadable review {}, renaming it to .corrupt", path, e);
					try
					{
						files.rename(path, path + ".corrupt");
					}
					catch (IOException renameFailed)
					{
						log.warn("Could not rename {}; skipping it", path, renameFailed);
					}
				}
			}
		}
		stored.sort(Comparator.comparing(Stored::getName).reversed());
		return stored.stream().map(Stored::getReview).collect(Collectors.toList());
	}

	@Override
	public void prune(int keepPerKey) throws IOException
	{
		Map<HistoryKey, Integer> kept = new HashMap<>();
		for (KillReview review : loadAll())
		{
			int count = kept.merge(new HistoryKey(review.getMode(), review.getContract()), 1, Integer::sum);
			if (count > keepPerKey)
			{
				files.delete(path(review));
			}
		}
	}

	static String path(KillReview review)
	{
		return REVIEWS + "/" + review.getMode().name() + "/" + String.format("%013d-%s%s", review.getStartEpochMs(), review.getKillId(), SUFFIX);
	}

	/** Empty for a review of another schema version. */
	private Optional<KillReview> parse(String json)
	{
		JsonObject object = gson.fromJson(json, JsonObject.class);
		JsonElement version = object.get("reviewSchemaVersion");
		if (version == null || version.getAsInt() != KillReview.SCHEMA_VERSION)
		{
			return Optional.empty();
		}
		return Optional.of(gson.fromJson(object, KillReview.class));
	}

	@Value
	private static class Stored
	{
		String name;
		KillReview review;
	}
}
