package com.yamareviewer.testing;

import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.model.Contract;
import com.yamareviewer.domain.model.Mode;
import com.yamareviewer.domain.model.Phase;
import com.yamareviewer.domain.review.DamageHit;
import com.yamareviewer.domain.review.DamageSource;
import com.yamareviewer.domain.review.DamageSummary;
import com.yamareviewer.domain.review.FlareCause;
import com.yamareviewer.domain.review.FlareFate;
import com.yamareviewer.domain.review.FlareResult;
import com.yamareviewer.domain.review.FlareSummary;
import com.yamareviewer.domain.review.FlareWave;
import com.yamareviewer.domain.review.HiddenReason;
import com.yamareviewer.domain.review.KillReview;
import com.yamareviewer.domain.review.PhaseSpan;
import com.yamareviewer.domain.review.PhaseTimes;
import com.yamareviewer.domain.review.PriceMode;
import com.yamareviewer.domain.review.ReviewStatus;
import com.yamareviewer.domain.review.Section;
import com.yamareviewer.domain.review.SupplyLine;
import com.yamareviewer.domain.review.SupplySummary;
import java.util.List;

/**
 * A complete 4:12 solo kill: P1 1:05, Judge 1 0:19, P2 1:10, Judge 2 0:14, P3 1:24; 212 damage taken
 * (partner 188); 6 flares, 5 killed; supplies 318,450 gp. The numbers match the chat example of spec 7.2.
 */
public final class Reviews
{
	public static final PhaseTimes PHASES = new PhaseTimes(List.of(
		new PhaseSpan(Phase.P1, 0, 108),
		new PhaseSpan(Phase.JUDGE_1, 108, 140),
		new PhaseSpan(Phase.P2, 140, 257),
		new PhaseSpan(Phase.JUDGE_2, 257, 280),
		new PhaseSpan(Phase.P3, 280, 420)), 420);

	private Reviews()
	{
	}

	public static KillReview.KillReviewBuilder builder(String killId, long startEpochMs, Mode mode, Contract contract)
	{
		return KillReview.builder()
			.reviewSchemaVersion(KillReview.SCHEMA_VERSION)
			.killId(killId)
			.startEpochMs(startEpochMs)
			.endEpochMs(startEpochMs + 252_000L)
			.endReason(EndReason.YAMA_DIED)
			.mode(mode)
			.contract(contract)
			.status(ReviewStatus.COMPLETE)
			.skippedEvents(0)
			.phases(Section.ok(PHASES))
			.damage(Section.ok(new DamageSummary(List.of(
				new DamageHit(20, Phase.P1, Actor.SELF, DamageSource.MELEE, 22),
				new DamageHit(300, Phase.P3, Actor.SELF, DamageSource.FLARE, 190),
				new DamageHit(310, Phase.P3, Actor.PARTNER, DamageSource.OTHER, 188)))))
			.flares(Section.ok(new FlareSummary(List.of(
				new FlareWave(30, 60, List.of(
					flare(1, 30, 45, FlareFate.KILLED),
					flare(2, 30, 60, FlareFate.EXPLODED))),
				new FlareWave(200, 230, List.of(
					flare(3, 200, 210, FlareFate.KILLED),
					flare(4, 200, 215, FlareFate.KILLED),
					flare(5, 201, 220, FlareFate.KILLED),
					flare(6, 201, 230, FlareFate.KILLED)))), 30)))
			.supplies(Section.ok(new SupplySummary(List.of(
				new SupplyLine("Shark", 2, "x", 1_600L, false),
				new SupplyLine("Super restore", 6, "doses", 316_850L, false)), 318_450L, PriceMode.GRAND_EXCHANGE)))
			.deathRecap(Section.hidden(HiddenReason.NOT_APPLICABLE));
	}

	public static KillReview sample()
	{
		return builder("kill-1", 1_700_000_000_000L, Mode.SOLO, Contract.NONE).build();
	}

	private static FlareResult flare(int index, int spawn, int despawn, FlareFate fate)
	{
		return new FlareResult(index, spawn, despawn, FlareCause.SUMMONED, fate, false);
	}
}
