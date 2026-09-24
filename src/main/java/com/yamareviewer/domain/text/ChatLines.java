package com.yamareviewer.domain.text;

import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.model.Mode;
import com.yamareviewer.domain.review.DamageSummary;
import com.yamareviewer.domain.review.DeathRecap;
import com.yamareviewer.domain.review.FlareSummary;
import com.yamareviewer.domain.review.KillReview;
import com.yamareviewer.domain.review.PhaseSpan;
import com.yamareviewer.domain.review.PhaseTimes;
import com.yamareviewer.domain.review.SupplySummary;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/** The chat summary of spec 7.2: up to four lines plus the recap block; hidden values print "n/a". Part 3 fills lines 2 and 4. */
public final class ChatLines
{
	static final String NOT_AVAILABLE = "n/a";

	private ChatLines()
	{
	}

	public static List<String> lines(KillReview review, ChatLineOptions options)
	{
		List<String> lines = new ArrayList<>();
		if (options.isPhaseLine())
		{
			lines.add(phaseLine(review));
		}
		if (options.isPrayerLine())
		{
			lines.add(P3Text.prayerLine(review));
		}
		if (options.isFlareLine())
		{
			lines.add(flareLine(review));
		}
		if (options.isSpecLine())
		{
			lines.add(specLine(review));
		}
		if (options.isDeathRecap())
		{
			Optional<DeathRecap> recap = review.getDeathRecap().asOptional();
			if (recap.isPresent())
			{
				lines.add("Death recap:");
				lines.addAll(ReviewFormatter.deathRecapLines(recap.get()));
			}
		}
		return lines;
	}

	/** "Yama (solo, Bloodied Blows) 4:12. P1 1:05, P2 1:10, P3 1:24." */
	static String phaseLine(KillReview review)
	{
		Optional<PhaseTimes> phases = review.getPhases().asOptional();
		String reached = phases.map(times -> times.getSpans().stream()
			.filter(span -> !span.getPhase().isJudge())
			.map(span -> span.getPhase().label() + " " + Formats.duration(span.ticks()))
			.collect(Collectors.joining(", "))).orElse("");
		return ReviewFormatter.headline(review) + "." + (reached.isEmpty() ? "" : " " + reached + ".");
	}

	/** "Flares 5/6 killed. Damage taken 212 (partner 188)." */
	static String flareLine(KillReview review)
	{
		String flares = review.getFlares().asOptional().map(summary -> summary.killed() + "/" + summary.spawned() + " killed").orElse(NOT_AVAILABLE);
		String damage = review.getDamage().asOptional().map(summary -> damageTaken(summary, review.getMode())).orElse(NOT_AVAILABLE);
		return "Flares " + flares + ". Damage taken " + damage + ".";
	}

	/** "Specs: Def 225→145 in P1 (3/3 Emberlight). Supplies 318k." */
	static String specLine(KillReview review)
	{
		String supplies = review.getSupplies().asOptional().map(summary -> Formats.compactGp(summary.getTotalCost())).orElse(NOT_AVAILABLE);
		return "Specs: " + P3Text.specFragment(review) + ". Supplies " + supplies + ".";
	}

	private static String damageTaken(DamageSummary damage, Mode mode)
	{
		String self = String.valueOf(damage.total(Actor.SELF));
		return mode == Mode.SOLO ? self : self + " (partner " + damage.total(Actor.PARTNER) + ")";
	}
}
