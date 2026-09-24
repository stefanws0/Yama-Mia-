package com.yamareviewer.application.command;

import com.yamareviewer.application.port.SnapshotSource;
import com.yamareviewer.domain.event.DomainEvent;
import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.event.EntryChoice;
import com.yamareviewer.domain.event.EntryChosen;
import com.yamareviewer.domain.event.FightEnded;
import com.yamareviewer.domain.event.FightStarted;
import com.yamareviewer.domain.event.PlayerLeft;
import com.yamareviewer.domain.event.PlayerSeen;
import com.yamareviewer.domain.event.SnapshotKind;
import com.yamareviewer.domain.model.KillHeader;
import com.yamareviewer.domain.model.KillLog;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * The write side. Its public methods are the commands; the only thing it produces is a frozen
 * {@link KillLog}, handed to the {@link KillEndedListener} when the kill ends.
 * Not thread-safe: call it from the client thread only.
 */
public final class KillSession
{
	private final KillEndedListener listener;
	private final SnapshotSource snapshots;
	private final Clock clock;
	private final Supplier<String> killIds;
	private final String pluginVersion;
	private final String idsFingerprint;
	private final BooleanSupplier captureMode;

	private final List<DomainEvent> events = new ArrayList<>();
	private SessionState state = SessionState.IDLE;
	private EntryChoice entryChoice;
	private int tick;
	private long startEpochMs;
	private String partnerName;
	private boolean capture;
	private EndReason pendingEnd;

	public KillSession(KillEndedListener listener, SnapshotSource snapshots, Clock clock, Supplier<String> killIds,
		String pluginVersion, String idsFingerprint, BooleanSupplier captureMode)
	{
		this.listener = listener;
		this.snapshots = snapshots;
		this.clock = clock;
		this.killIds = killIds;
		this.pluginVersion = pluginVersion;
		this.idsFingerprint = idsFingerprint;
		this.captureMode = captureMode;
	}

	public SessionState state()
	{
		return state;
	}

	public boolean isFighting()
	{
		return state == SessionState.FIGHTING;
	}

	public int currentTick()
	{
		return tick;
	}

	/** The first other player seen in this fight; used to label actors while recording. */
	public Optional<String> partnerName()
	{
		return Optional.ofNullable(partnerName);
	}

	/** Travel or Join at the Voice of Yama. Remembered until the player leaves Yama's Domain. */
	public void chooseEntry(EntryChoice choice)
	{
		if (state != SessionState.FIGHTING)
		{
			entryChoice = choice;
		}
	}

	public void updateRegion(boolean inYamasDomain)
	{
		if (inYamasDomain && state == SessionState.IDLE)
		{
			state = SessionState.ARMED;
		}
		else if (!inYamasDomain && state != SessionState.IDLE)
		{
			leave();
		}
	}

	public void yamaSpawned(FightStart start)
	{
		if (state != SessionState.ARMED)
		{
			return;
		}
		state = SessionState.FIGHTING;
		tick = 0;
		events.clear();
		pendingEnd = null;
		partnerName = null;
		startEpochMs = clock.millis();
		capture = captureMode.getAsBoolean();
		if (entryChoice != null)
		{
			events.add(new EntryChosen(0, entryChoice));
		}
		events.add(new FightStarted(0, start.getSelfName(), start.getSelfPosition()));
		start.getPlayersPresent().forEach(this::playerSeen);
		snapshots.take(0, SnapshotKind.START).ifPresent(events::add);
	}

	public void playerSeen(String name)
	{
		if (state != SessionState.FIGHTING)
		{
			return;
		}
		if (partnerName == null)
		{
			partnerName = name;
		}
		events.add(new PlayerSeen(tick, name));
	}

	public void playerLeft(String name)
	{
		if (state == SessionState.FIGHTING)
		{
			events.add(new PlayerLeft(tick, name));
		}
	}

	public void record(DomainEvent event)
	{
		if (state == SessionState.FIGHTING)
		{
			events.add(event);
		}
	}

	/** Called after the tick's TickState is recorded. Completes a latched end, otherwise advances the tick. */
	public void endTick()
	{
		if (state != SessionState.FIGHTING)
		{
			return;
		}
		if (pendingEnd != null)
		{
			complete(pendingEnd);
		}
		else
		{
			tick++;
		}
	}

	public void yamaDied()
	{
		latch(EndReason.YAMA_DIED);
	}

	public void playerDied()
	{
		latch(EndReason.PLAYER_DIED);
	}

	/** Logout, world hop, leaving the region or plugin shutdown: no further tick will come. */
	public void leave()
	{
		if (state == SessionState.FIGHTING)
		{
			if (pendingEnd == null)
			{
				snapshots.take(tick, SnapshotKind.END).ifPresent(events::add);
			}
			complete(pendingEnd != null ? pendingEnd : EndReason.LEFT);
		}
		state = SessionState.IDLE;
		entryChoice = null;
	}

	private void latch(EndReason reason)
	{
		if (state == SessionState.FIGHTING && pendingEnd == null)
		{
			pendingEnd = reason;
			snapshots.take(tick, SnapshotKind.END).ifPresent(events::add);
		}
	}

	private void complete(EndReason reason)
	{
		events.add(new FightEnded(tick, reason));
		KillHeader header = new KillHeader(killIds.get(), startEpochMs, clock.millis(), pluginVersion,
			KillLog.SCHEMA_VERSION, idsFingerprint, capture);
		KillLog kill = KillLog.of(header, events, 0);
		events.clear();
		partnerName = null;
		pendingEnd = null;
		state = SessionState.ARMED;
		listener.killEnded(kill);
	}
}
