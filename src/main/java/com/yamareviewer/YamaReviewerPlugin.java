package com.yamareviewer;

import com.google.gson.Gson;
import com.google.inject.Provides;
import com.yamareviewer.adapter.ids.BuiltInIds;
import com.yamareviewer.adapter.persistence.EventCodec;
import com.yamareviewer.adapter.persistence.FileReportRepository;
import com.yamareviewer.adapter.persistence.FileStore;
import com.yamareviewer.adapter.persistence.FilepathFileStore;
import com.yamareviewer.adapter.persistence.GsonLogRepository;
import com.yamareviewer.adapter.persistence.GsonReviewRepository;
import com.yamareviewer.adapter.publish.ChatReviewPublisherFactory;
import com.yamareviewer.adapter.publish.CompositeReviewPublisher;
import com.yamareviewer.adapter.publish.PanelReviewPublisher;
import com.yamareviewer.adapter.recording.ActorResolver;
import com.yamareviewer.adapter.recording.EventTranslator;
import com.yamareviewer.adapter.recording.GameEventListener;
import com.yamareviewer.adapter.recording.ItemLookup;
import com.yamareviewer.adapter.recording.ItemManagerLookup;
import com.yamareviewer.adapter.recording.PositionReader;
import com.yamareviewer.adapter.recording.SnapshotReader;
import com.yamareviewer.adapter.recording.TickSampler;
import com.yamareviewer.adapter.ui.KillReviewOpener;
import com.yamareviewer.adapter.ui.PanelIcon;
import com.yamareviewer.adapter.ui.ReportProblemFooter;
import com.yamareviewer.adapter.ui.ReviewPanel;
import com.yamareviewer.adapter.ui.SystemClipboard;
import com.yamareviewer.application.command.KillSession;
import com.yamareviewer.application.handler.HistoryLoader;
import com.yamareviewer.application.handler.KillEndedHandler;
import com.yamareviewer.application.handler.ProblemReporter;
import com.yamareviewer.application.port.LogRepository;
import com.yamareviewer.application.port.ReportRepository;
import com.yamareviewer.application.port.ReviewPublisher;
import com.yamareviewer.application.port.ReviewRepository;
import com.yamareviewer.domain.diagnosis.Versions;
import com.yamareviewer.domain.health.HealthChecks;
import com.yamareviewer.domain.ids.IdRegistry;
import com.yamareviewer.domain.ids.Rules;
import com.yamareviewer.domain.projection.Projections;
import com.yamareviewer.domain.projection.ReviewBuilder;
import com.yamareviewer.domain.projection.ReviewSettings;
import com.yamareviewer.domain.text.ChatLineOptions;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.RuneLiteProperties;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.NpcUtil;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;

/** Composition root: builds the object graph, registers the listener and attaches the panel. No game logic lives here. */
@Slf4j
@PluginDescriptor(
	name = "Yama Reviewer",
	description = "Records Yama kills and reviews them after the kill ends. Silent during the fight.",
	tags = {"yama", "pvm", "review", "prayer", "duo"},
	internalName = "yama-reviewer"
)
public class YamaReviewerPlugin extends Plugin
{
	static final String VERSION = "0.2.0";

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

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private ChatReviewPublisherFactory chatPublishers;

	private final AtomicBoolean active = new AtomicBoolean();
	private ExecutorService executor;
	private KillSession session;
	private GameEventListener listener;
	private NavigationButton navigationButton;
	private ProblemReporter reporter;

	@Provides
	YamaReviewerConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(YamaReviewerConfig.class);
	}

	@Override
	protected void startUp() throws Exception
	{
		active.set(true);
		executor = Executors.newSingleThreadExecutor(runnable ->
		{
			Thread thread = new Thread(runnable, "yama-reviewer");
			thread.setDaemon(true);
			return thread;
		});
		IdRegistry ids = BuiltInIds.registry();
		FileStore files = new FilepathFileStore(getPluginDirectory());
		LogRepository logs = new GsonLogRepository(files, new EventCodec(gson));
		ReviewRepository reviews = new GsonReviewRepository(files, gson);
		ReviewBuilder builder = new ReviewBuilder(Projections.standard(), ids, Rules.DEFAULT);
		ReportRepository reports = new FileReportRepository(files);
		reporter = new ProblemReporter(reports, ids,
			() -> new Versions(VERSION, Objects.toString(RuneLiteProperties.getVersion(), "dev"), String.valueOf(client.getRevision())),
			Clock.systemDefaultZone());

		ReviewPanel panel = new ReviewPanel(SystemClipboard::copy);
		KillReviewOpener opener = new KillReviewOpener(executor, reviews, panel);
		panel.onKillSelected(opener::open);
		ReviewPublisher publisher = new CompositeReviewPublisher(List.of(
			chatPublishers.create(this::chatLineOptions),
			new PanelReviewPublisher(panel)));
		KillEndedHandler handler = new KillEndedHandler(executor, logs, reviews, builder, publisher, active::get,
			config::rawLogsKept, config::historySize, this::reviewSettings, HealthChecks.standard(), reporter);

		ItemLookup items = new ItemManagerLookup(itemManager);
		session = new KillSession(handler, new SnapshotReader(client, items), Clock.systemUTC(),
			() -> UUID.randomUUID().toString(), VERSION, ids.fingerprint(), config::captureMode);
		PositionReader positions = new PositionReader(client);
		ActorResolver actors = new ActorResolver(client, ids, session::partnerName);
		EventTranslator translator = new EventTranslator(ids, actors, session::currentTick, config::captureMode, items::name, positions);
		listener = new GameEventListener(client, ids, session, translator, new TickSampler(client, actors, positions),
			positions, npcUtil, handler::pending, this::reportUnknownYama);

		panel.attachFooter(new ReportProblemFooter(executor, reports, ProjectLinks.REPOSITORY_URL));

		navigationButton = NavigationButton.builder()
			.tooltip("Yama Reviewer")
			.icon(PanelIcon.create())
			.priority(7)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navigationButton);
		eventBus.register(listener);
		new HistoryLoader(executor, reviews, publisher, config::historySize).load();
		log.debug("Yama Reviewer started");
	}

	@Override
	protected void shutDown()
	{
		active.set(false);
		clientToolbar.removeNavigation(navigationButton);
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

	private ChatLineOptions chatLineOptions()
	{
		return new ChatLineOptions(config.chatPhaseLine(), config.chatPrayerLine(), config.chatFlareLine(), config.chatSpecLine(),
			config.chatDeathRecap());
	}

	private ReviewSettings reviewSettings()
	{
		return new ReviewSettings(config.priceSource(), config.modeOverride().mode());
	}

	/** Client thread → executor: the recording self-check of spec 5.3 writes its report off the client thread. */
	private void reportUnknownYama(int npcId)
	{
		try
		{
			executor.submit(() -> reporter.reportUnknownYama(npcId));
		}
		catch (RejectedExecutionException e)
		{
			log.debug("Plugin stopped before the unknown-Yama report could be written");
		}
	}
}
