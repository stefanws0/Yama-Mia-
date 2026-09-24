package com.yamareviewer;

import com.google.gson.Gson;
import com.google.inject.Provides;
import com.yamareviewer.adapter.ids.BuiltInIds;
import com.yamareviewer.adapter.persistence.EventCodec;
import com.yamareviewer.adapter.persistence.FilepathFileStore;
import com.yamareviewer.adapter.persistence.GsonLogRepository;
import com.yamareviewer.adapter.persistence.GsonReviewRepository;
import com.yamareviewer.adapter.publish.CompositeReviewPublisher;
import com.yamareviewer.adapter.recording.ActorResolver;
import com.yamareviewer.adapter.recording.EventTranslator;
import com.yamareviewer.adapter.recording.GameEventListener;
import com.yamareviewer.adapter.recording.ItemLookup;
import com.yamareviewer.adapter.recording.ItemManagerLookup;
import com.yamareviewer.adapter.recording.PositionReader;
import com.yamareviewer.adapter.recording.SnapshotReader;
import com.yamareviewer.adapter.recording.TickSampler;
import com.yamareviewer.application.command.KillSession;
import com.yamareviewer.application.handler.KillEndedHandler;
import com.yamareviewer.application.port.LogRepository;
import com.yamareviewer.domain.ids.IdRegistry;
import com.yamareviewer.domain.ids.Rules;
import com.yamareviewer.domain.projection.Projections;
import com.yamareviewer.domain.projection.ReviewBuilder;
import com.yamareviewer.domain.projection.ReviewSettings;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.NpcUtil;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

/** Composition root: builds the object graph and registers the listener. No game logic lives here. */
@Slf4j
@PluginDescriptor(
	name = "Yama Reviewer",
	description = "Records Yama kills and reviews them after the kill ends. Silent during the fight.",
	tags = {"yama", "pvm", "review", "prayer", "duo"},
	internalName = "yama-reviewer"
)
public class YamaReviewerPlugin extends Plugin
{
	static final String VERSION = "0.1.0";

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private EventBus eventBus;

	@Inject
	private YamaReviewerConfig config;

	@Inject
	private Gson gson;

	@Inject
	private ItemManager itemManager;

	@Inject
	private NpcUtil npcUtil;

	private ExecutorService executor;
	private KillSession session;
	private GameEventListener listener;

	@Provides
	YamaReviewerConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(YamaReviewerConfig.class);
	}

	@Override
	protected void startUp() throws Exception
	{
		executor = Executors.newSingleThreadExecutor(runnable ->
		{
			Thread thread = new Thread(runnable, "yama-reviewer");
			thread.setDaemon(true);
			return thread;
		});
		IdRegistry ids = BuiltInIds.registry();
		LogRepository logs = new GsonLogRepository(new FilepathFileStore(getPluginDirectory()), new EventCodec(gson));
		KillEndedHandler handler = new KillEndedHandler(executor, logs, new GsonReviewRepository(new FilepathFileStore(getPluginDirectory()), gson),
			new ReviewBuilder(Projections.standard(), ids, Rules.DEFAULT), new CompositeReviewPublisher(List.of()), () -> false,
			config::rawLogsKept, () -> 50, () -> ReviewSettings.DEFAULT);
		ItemLookup items = new ItemManagerLookup(itemManager);

		session = new KillSession(handler, new SnapshotReader(client, items), Clock.systemUTC(),
			() -> UUID.randomUUID().toString(), VERSION, ids.fingerprint(), config::captureMode);
		PositionReader positions = new PositionReader(client);
		ActorResolver actors = new ActorResolver(client, ids, session::partnerName);
		EventTranslator translator = new EventTranslator(ids, actors, session::currentTick, config::captureMode, items::name, positions);
		listener = new GameEventListener(client, ids, session, translator, new TickSampler(client, actors, positions),
			positions, npcUtil, handler::pending);

		eventBus.register(listener);
		log.debug("Yama Reviewer started");
	}

	@Override
	protected void shutDown()
	{
		eventBus.unregister(listener);
		KillSession ending = session;
		ExecutorService stopping = executor;
		clientThread.invoke(() ->
		{
			ending.leave();
			stopping.shutdown();
		});
		log.debug("Yama Reviewer stopped");
	}
}
