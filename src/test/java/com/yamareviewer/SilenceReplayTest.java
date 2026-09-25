package com.yamareviewer;

import com.google.gson.Gson;
import com.yamareviewer.adapter.persistence.EventCodec;
import com.yamareviewer.adapter.persistence.GsonLogRepository;
import com.yamareviewer.adapter.persistence.GsonReviewRepository;
import com.yamareviewer.adapter.persistence.InMemoryFileStore;
import com.yamareviewer.adapter.recording.ActorResolver;
import com.yamareviewer.adapter.recording.EventTranslator;
import com.yamareviewer.adapter.recording.GameEventListener;
import com.yamareviewer.adapter.recording.PositionReader;
import com.yamareviewer.adapter.recording.TickSampler;
import com.yamareviewer.application.command.KillSession;
import com.yamareviewer.application.handler.KillEndedHandler;
import com.yamareviewer.application.handler.ProblemReporter;
import com.yamareviewer.application.port.LogRepository;
import com.yamareviewer.application.port.ReportRepository;
import com.yamareviewer.application.port.ReviewPublisher;
import com.yamareviewer.application.port.ReviewRepository;
import com.yamareviewer.domain.diagnosis.Versions;
import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.event.SuppliesSnapshot;
import com.yamareviewer.domain.event.TickState;
import com.yamareviewer.domain.history.HistoryIndex;
import com.yamareviewer.domain.ids.IdRegistry;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.ids.Rules;
import com.yamareviewer.domain.model.Mode;
import com.yamareviewer.domain.projection.Projections;
import com.yamareviewer.domain.projection.ReviewBuilder;
import com.yamareviewer.domain.projection.ReviewSettings;
import com.yamareviewer.domain.review.KillReview;
import com.yamareviewer.testing.TestIds;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.runelite.api.Client;
import net.runelite.api.Hitsplat;
import net.runelite.api.HitsplatID;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.NpcSpawned;
import net.runelite.client.game.NpcUtil;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Spec 4.4: a synthetic fight through GameEventListener publishes nothing until the kill has ended, then exactly one review. */
public class SilenceReplayTest
{
	private final Client client = mock(Client.class);
	private final Player self = mock(Player.class);
	private final NPC yama = mock(NPC.class);
	private final PositionReader positions = mock(PositionReader.class);
	private final TickSampler sampler = mock(TickSampler.class);
	private final NpcUtil npcUtil = mock(NpcUtil.class);
	private final IdRegistry ids = TestIds.registry();
	private final InMemoryFileStore files = new InMemoryFileStore();
	private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> new Thread(r, "yama-reviewer-test"));
	private final RecordingPublisher publisher = new RecordingPublisher();
	private KillEndedHandler handler;
	private GameEventListener listener;

	@Before
	public void setUp()
	{
		when(client.getLocalPlayer()).thenReturn(self);
		when(self.getName()).thenReturn("Me");
		when(positions.regionId(any())).thenReturn(TestIds.id(Role.YAMAS_DOMAIN));
		when(yama.getId()).thenReturn(TestIds.id(Role.YAMA));
		when(yama.getAnimation()).thenReturn(TestIds.id(Role.YAMA_STANDARD_ATTACK));
		when(sampler.sample(anyInt(), any(), any())).thenAnswer(invocation ->
			new TickState(invocation.getArgument(0), Set.of(), 99, 99, 100, 100, -1, null, null, null));

		Gson gson = new Gson();
		LogRepository logs = new GsonLogRepository(files, new EventCodec(gson));
		ReviewRepository reviews = new GsonReviewRepository(files, gson);
		ReviewBuilder builder = new ReviewBuilder(Projections.standard(), ids, Rules.DEFAULT);
		handler = new KillEndedHandler(executor, logs, reviews, builder, publisher, () -> true, () -> 20, () -> 50, () -> ReviewSettings.DEFAULT,
			List.of(), new ProblemReporter(NO_REPORTS, TestIds.registry(), () -> new Versions("0.1.0", "test", "0"), Clock.systemUTC()));
		KillSession session = new KillSession(handler, (tick, kind) -> Optional.of(new SuppliesSnapshot(tick, kind, List.of())),
			Clock.systemUTC(), () -> "kill-1", "0.2.0", ids.fingerprint(), () -> false);
		ActorResolver actors = new ActorResolver(client, ids, session::partnerName);
		EventTranslator translator = new EventTranslator(ids, actors, session::currentTick, () -> false, itemId -> "item", positions);
		listener = new GameEventListener(client, ids, session, translator, sampler, positions, npcUtil, handler::pending);
	}

	@After
	public void tearDown()
	{
		executor.shutdownNow();
	}

	private static final ReportRepository NO_REPORTS = new ReportRepository()
	{
		@Override
		public void save(String fileName, String text)
		{
		}

		@Override
		public Optional<String> latest()
		{
			return Optional.empty();
		}
	};

	private void drain() throws Exception
	{
		executor.submit(() ->
		{
		}).get(5, TimeUnit.SECONDS);
	}

	private HitsplatApplied hitOnSelf(int amount)
	{
		Hitsplat hitsplat = mock(Hitsplat.class);
		when(hitsplat.getHitsplatType()).thenReturn(HitsplatID.DAMAGE_ME);
		when(hitsplat.getAmount()).thenReturn(amount);
		when(hitsplat.isMine()).thenReturn(false);
		HitsplatApplied event = new HitsplatApplied();
		event.setActor(self);
		event.setHitsplat(hitsplat);
		return event;
	}

	private AnimationChanged yamaAttacks()
	{
		AnimationChanged event = new AnimationChanged();
		event.setActor(yama);
		return event;
	}

	@Test
	public void nothingIsPublishedBeforeTheKillEndsAndExactlyOneReviewAfter() throws Exception
	{
		listener.onNpcSpawned(new NpcSpawned(yama));
		for (int tick = 0; tick < 40; tick++)
		{
			if (tick % 8 == 5)
			{
				listener.onAnimationChanged(yamaAttacks());
			}
			if (tick % 10 == 7)
			{
				listener.onHitsplatApplied(hitOnSelf(12));
			}
			listener.onGameTick(new GameTick());
		}
		drain();

		assertEquals(0, publisher.published.size());
		assertEquals(0, publisher.histories.size());
		assertTrue(files.paths().isEmpty());

		when(npcUtil.isDying(yama)).thenReturn(true);
		listener.onNpcDespawned(new NpcDespawned(yama));
		listener.onGameTick(new GameTick());
		handler.pending().get(5, TimeUnit.SECONDS);

		assertEquals(1, publisher.published.size());
		KillReview review = publisher.published.get(0);
		assertEquals("kill-1", review.getKillId());
		assertEquals(EndReason.YAMA_DIED, review.getEndReason());
		assertEquals(Mode.SOLO, review.getMode());
		assertEquals(48, review.getDamage().value().total(Actor.SELF));
		assertEquals(1, publisher.histories.size());
		assertTrue(files.paths().stream().anyMatch(path -> path.startsWith("raw/")));
		assertTrue(files.paths().stream().anyMatch(path -> path.startsWith("reviews/SOLO/")));
	}

	private static final class RecordingPublisher implements ReviewPublisher
	{
		private final List<KillReview> published = new CopyOnWriteArrayList<>();
		private final List<HistoryIndex> histories = new CopyOnWriteArrayList<>();

		@Override
		public void publish(KillReview review, HistoryIndex history)
		{
			published.add(review);
			histories.add(history);
		}

		@Override
		public void showHistory(HistoryIndex history)
		{
			histories.add(history);
		}
	}
}
