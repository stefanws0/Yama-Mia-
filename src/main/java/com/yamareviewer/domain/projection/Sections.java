package com.yamareviewer.domain.projection;

import com.yamareviewer.domain.model.Contract;
import com.yamareviewer.domain.model.Mode;
import com.yamareviewer.domain.review.AttackTimeline;
import com.yamareviewer.domain.review.CrashSummary;
import com.yamareviewer.domain.review.DamageSummary;
import com.yamareviewer.domain.review.DeathRecap;
import com.yamareviewer.domain.review.FlareSummary;
import com.yamareviewer.domain.review.GlyphCount;
import com.yamareviewer.domain.review.Opener;
import com.yamareviewer.domain.review.PhaseTimes;
import com.yamareviewer.domain.review.PrayerReview;
import com.yamareviewer.domain.review.SpecSummary;
import com.yamareviewer.domain.review.SupplySummary;
import com.yamareviewer.domain.review.TickLog;
import com.yamareviewer.domain.review.WaveSummary;

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
	/** Intermediate (spec 6.1): feeds Opener, not shown on its own. */
	public static final SectionKey<GlyphCount> GLYPHS = SectionKey.of("glyphs");
	/** Intermediate (spec 6.1): feeds PrayerReview, Opener, TickLog, Crashes, Waves and DamageAttribution. */
	public static final SectionKey<AttackTimeline> ATTACKS = SectionKey.of("attacks");
	public static final SectionKey<CrashSummary> CRASHES = SectionKey.of("crashes");
	public static final SectionKey<WaveSummary> WAVES = SectionKey.of("waves");
	public static final SectionKey<SpecSummary> SPECS = SectionKey.of("specs");
	public static final SectionKey<PrayerReview> PRAYER_REVIEW = SectionKey.of("prayer-review");
	public static final SectionKey<Opener> OPENER = SectionKey.of("opener");
	public static final SectionKey<TickLog> TICK_LOG = SectionKey.of("tick-log");

	private Sections()
	{
	}
}
