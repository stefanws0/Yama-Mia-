package com.yamareviewer.domain.health;

import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.event.ProtectionPrayer;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.testing.KillLogBuilder;
import java.util.Optional;
import java.util.Set;
import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class TickStateIndexTest
{
	@Test
	public void findsTheStateOfATickAndTreatsMissingTicksAsNoPrayers()
	{
		KillLog log = KillLogBuilder.kill().prayers(ProtectionPrayer.MAGIC).endTick().prayers().endTick().end(EndReason.LEFT);

		TickStateIndex index = TickStateIndex.of(log);

		assertEquals(Set.of(ProtectionPrayer.MAGIC), index.prayersAt(0));
		assertEquals(Set.of(), index.prayersAt(1));
		assertEquals(Optional.empty(), index.at(7));
		assertEquals(Set.of(), index.prayersAt(7));
	}
}
