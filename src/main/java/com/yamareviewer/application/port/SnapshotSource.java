package com.yamareviewer.application.port;

import com.yamareviewer.domain.event.SnapshotKind;
import com.yamareviewer.domain.event.SuppliesSnapshot;
import java.util.Optional;

/** Reads the player's supplies. Empty when they can't be read reliably (logged out, hopping). */
public interface SnapshotSource
{
	Optional<SuppliesSnapshot> take(int tick, SnapshotKind kind);
}
