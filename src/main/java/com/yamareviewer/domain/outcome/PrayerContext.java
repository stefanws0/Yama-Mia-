package com.yamareviewer.domain.outcome;

import com.yamareviewer.domain.event.ProtectionPrayer;
import com.yamareviewer.domain.model.Style;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.Value;

/** What the outcome rules of spec 6.5 look at for one P3 attack aimed at you. */
@Value
public class PrayerContext
{
	/** The prayer-check tick L. */
	int checkTick;
	ProtectionPrayer correctPrayer;
	/** The protection prayers of each TickState by tick; a tick without a state reads as no prayers. */
	Map<Integer, Set<ProtectionPrayer>> prayersByTick;
	/** Ticks of PRAYER_DISABLED_MESSAGE lines. */
	List<Integer> prayerDisabledTicks;
	int waveDisableWindow;

	public Set<ProtectionPrayer> prayersAt(int tick)
	{
		Set<ProtectionPrayer> active = prayersByTick.get(tick);
		return active == null ? Set.of() : active;
	}

	public boolean correctAt(int tick)
	{
		return prayersAt(tick).contains(correctPrayer);
	}

	/** Protect from Magic for magic, Protect from Missiles for ranged. */
	public static ProtectionPrayer correctFor(Style style)
	{
		return style == Style.MAGIC ? ProtectionPrayer.MAGIC : ProtectionPrayer.MISSILES;
	}
}
