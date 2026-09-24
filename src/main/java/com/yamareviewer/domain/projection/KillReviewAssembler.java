package com.yamareviewer.domain.projection;

import com.yamareviewer.domain.model.Contract;
import com.yamareviewer.domain.model.KillHeader;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.model.Mode;
import com.yamareviewer.domain.review.KillReview;
import com.yamareviewer.domain.review.ReviewStatus;

/** Builds the KillReview from a finished context. Part 3 sets its fields here too. */
public final class KillReviewAssembler
{
	private KillReviewAssembler()
	{
	}

	public static KillReview assemble(KillLog log, ProjectionContext context)
	{
		KillHeader header = log.getHeader();
		return KillReview.builder()
			.reviewSchemaVersion(KillReview.SCHEMA_VERSION)
			.killId(header.getKillId())
			.startEpochMs(header.getStartEpochMs())
			.endEpochMs(header.getEndEpochMs())
			.endReason(log.endReason())
			.mode(context.value(Sections.MODE).orElse(Mode.SOLO))
			.contract(context.value(Sections.CONTRACT).orElse(Contract.NONE))
			.status(ReviewStatus.COMPLETE)
			.skippedEvents(log.getSkippedEvents())
			.phases(context.section(Sections.PHASES))
			.damage(context.section(Sections.DAMAGE))
			.flares(context.section(Sections.FLARES))
			.supplies(context.section(Sections.SUPPLIES))
			.deathRecap(context.section(Sections.DEATH_RECAP))
			.prayerReview(context.section(Sections.PRAYER_REVIEW))
			.opener(context.section(Sections.OPENER))
			.tickLog(context.section(Sections.TICK_LOG))
			.crashes(context.section(Sections.CRASHES))
			.waves(context.section(Sections.WAVES))
			.specs(context.section(Sections.SPECS))
			.build()
			.withRecomputedStatus();
	}
}
