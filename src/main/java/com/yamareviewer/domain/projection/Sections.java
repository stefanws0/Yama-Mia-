package com.yamareviewer.domain.projection;

import com.yamareviewer.domain.model.Contract;
import com.yamareviewer.domain.model.Mode;
import com.yamareviewer.domain.review.DamageSummary;
import com.yamareviewer.domain.review.DeathRecap;
import com.yamareviewer.domain.review.FlareSummary;
import com.yamareviewer.domain.review.PhaseTimes;
import com.yamareviewer.domain.review.SupplySummary;

/** All section keys. Part 3 adds its keys here. */
public final class Sections
{
	public static final SectionKey<PhaseTimes> PHASES = SectionKey.of("phases");
	public static final SectionKey<Contract> CONTRACT = SectionKey.of("contract");
	public static final SectionKey<Mode> MODE = SectionKey.of("mode");
	public static final SectionKey<FlareSummary> FLARES = SectionKey.of("flares");
	public static final SectionKey<DamageSummary> DAMAGE = SectionKey.of("damage");
	public static final SectionKey<SupplySummary> SUPPLIES = SectionKey.of("supplies");
	public static final SectionKey<DeathRecap> DEATH_RECAP = SectionKey.of("death-recap");

	private Sections()
	{
	}
}
