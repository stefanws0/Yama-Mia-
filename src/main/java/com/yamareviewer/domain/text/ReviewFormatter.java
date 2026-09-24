package com.yamareviewer.domain.text;

import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.model.Contract;
import com.yamareviewer.domain.model.Mode;
import com.yamareviewer.domain.model.Phase;
import com.yamareviewer.domain.review.DamageSource;
import com.yamareviewer.domain.review.DamageSummary;
import com.yamareviewer.domain.review.DeathRecap;
import com.yamareviewer.domain.review.FlareSummary;
import com.yamareviewer.domain.review.FlareWave;
import com.yamareviewer.domain.review.KillReview;
import com.yamareviewer.domain.review.PhaseSpan;
import com.yamareviewer.domain.review.PhaseTimes;
import com.yamareviewer.domain.review.PriceMode;
import com.yamareviewer.domain.review.RecapHit;
import com.yamareviewer.domain.review.RecapTick;
import com.yamareviewer.domain.review.ReviewStatus;
import com.yamareviewer.domain.review.Section;
import com.yamareviewer.domain.review.SupplyLine;
import com.yamareviewer.domain.review.SupplySummary;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Renders a KillReview to plain strings for the panel (spec 7.3). Part 3 adds its sections in format(). */
public final class ReviewFormatter
{
	private ReviewFormatter()
	{
	}

	public static ReviewView format(KillReview review)
	{
		List<ViewSection> sections = new ArrayList<>();
		if (review.getEndReason() == EndReason.PLAYER_DIED || review.getDeathRecap().isOk())
		{
			sections.add(section("Death recap", review.getDeathRecap(), ReviewFormatter::deathRecapLines));
		}
		sections.add(section("Phases", review.getPhases(), ReviewFormatter::phaseLines));
		sections.add(section("Damage taken", review.getDamage(), damage -> damageLines(damage, review.getMode())));
		sections.add(section("Flares", review.getFlares(), ReviewFormatter::flareLines));
		sections.add(section("Supplies", review.getSupplies(), ReviewFormatter::supplyLines));
		return new ReviewView(headline(review), status(review), List.copyOf(sections));
	}

	/** "Yama (solo, Bloodied Blows) 4:12", "… died in P3 at 3:10", "… left in P2 at 2:00"; "n/a" without phases. */
	public static String headline(KillReview review)
	{
		String who = "Yama (" + modeAndContract(review) + ")";
		Optional<PhaseTimes> phases = review.getPhases().asOptional();
		switch (review.getEndReason())
		{
			case YAMA_DIED:
				return who + " " + phases.map(times -> Formats.duration(times.getTotalTicks())).orElse("n/a");
			case PLAYER_DIED:
				return who + " died" + phases.map(ReviewFormatter::whereAndWhen).orElse("");
			default:
				return who + " left" + phases.map(ReviewFormatter::whereAndWhen).orElse("");
		}
	}

	/** "solo", "duo host, Bloodied Blows", "solo, unknown contract". */
	public static String modeAndContract(KillReview review)
	{
		String mode = review.getMode().label();
		Contract contract = review.getContract();
		if (!contract.isContract())
		{
			return mode;
		}
		return mode + ", " + (contract == Contract.UNKNOWN_CONTRACT ? "unknown contract" : contract.shortName());
	}

	public static String status(KillReview review)
	{
		return review.getStatus() == ReviewStatus.COMPLETE ? "Complete" : "Incomplete";
	}

	public static <T> ViewSection section(String title, Section<T> section, Function<T, List<String>> lines)
	{
		if (section.isOk())
		{
			return new ViewSection(title, List.copyOf(lines.apply(section.value())), null);
		}
		return new ViewSection(title, List.of(), section.hiddenReason().get().label());
	}

	static List<String> phaseLines(PhaseTimes times)
	{
		List<String> lines = new ArrayList<>();
		for (PhaseSpan span : times.getSpans())
		{
			lines.add(span.getPhase().label() + " " + Formats.duration(span.ticks()) + " (" + span.ticks() + " ticks)");
		}
		lines.add("Total " + Formats.duration(times.getTotalTicks()) + " (" + times.getTotalTicks() + " ticks)");
		return lines;
	}

	static List<String> damageLines(DamageSummary damage, Mode mode)
	{
		List<String> lines = new ArrayList<>(playerLines("You", Actor.SELF, damage));
		if (mode != Mode.SOLO)
		{
			lines.addAll(playerLines("Partner", Actor.PARTNER, damage));
		}
		lines.add("Shared mechanics: " + damage.shared());
		return lines;
	}

	private static List<String> playerLines(String label, Actor player, DamageSummary damage)
	{
		List<String> lines = new ArrayList<>();
		lines.add(label + ": " + damage.total(player));
		List<String> perPhase = new ArrayList<>();
		for (Phase phase : Phase.values())
		{
			int amount = damage.total(player, phase);
			if (amount > 0)
			{
				perPhase.add(phase.label() + " " + amount);
			}
		}
		if (!perPhase.isEmpty())
		{
			lines.add("  " + String.join(" · ", perPhase));
		}
		for (Map.Entry<DamageSource, Integer> entry : damage.bySource(player).entrySet())
		{
			lines.add("  " + entry.getKey().label() + " " + entry.getValue());
		}
		return lines;
	}

	static List<String> flareLines(FlareSummary flares)
	{
		List<String> lines = new ArrayList<>();
		lines.add("Spawned " + flares.spawned() + ", killed " + flares.killed() + ", exploded " + flares.exploded());
		lines.add("Yama healed " + flares.getYamaHealing());
		if (flares.purgingStaffKills() > 0)
		{
			lines.add(flares.purgingStaffKills() + " killed with the purging staff");
		}
		int number = 1;
		for (FlareWave wave : flares.getWaves())
		{
			lines.add("Wave " + number++ + " (" + wave.cause().name().toLowerCase(Locale.ROOT) + "): " + wave.getFlares().size()
				+ (wave.getFlares().size() == 1 ? " flare, " : " flares, ") + wave.ticks() + " ticks");
		}
		return lines;
	}

	static List<String> supplyLines(SupplySummary supplies)
	{
		List<String> lines = new ArrayList<>();
		if (supplies.getLines().isEmpty())
		{
			lines.add("Nothing used");
		}
		for (SupplyLine line : supplies.getLines())
		{
			String quantity = "doses".equals(line.getUnit()) ? " " + line.getQuantity() + " doses" : " x" + line.getQuantity();
			lines.add(line.getName() + quantity + ": " + Formats.gp(line.getCost()) + (line.isNoEffect() ? " (no effect under this contract)" : ""));
		}
		lines.add("Total: " + Formats.gp(supplies.getTotalCost()) + " ("
			+ (supplies.getPriceMode() == PriceMode.GRAND_EXCHANGE ? "Grand Exchange" : "high alchemy") + ")");
		return lines;
	}

	static List<String> deathRecapLines(DeathRecap recap)
	{
		List<String> lines = recap.getTicks().stream().map(ReviewFormatter::recapTickLine).collect(Collectors.toList());
		if (recap.isOutOfRunEnergy())
		{
			lines.add("Died out of run energy");
		}
		return lines;
	}

	static String recapTickLine(RecapTick tick)
	{
		StringBuilder line = new StringBuilder("Tick ").append(tick.getTick())
			.append(": HP ").append(tick.getHitpoints() < 0 ? "?" : String.valueOf(tick.getHitpoints()))
			.append(", prayer ").append(tick.getPrayerPoints() < 0 ? "?" : String.valueOf(tick.getPrayerPoints()));
		if (tick.getRunEnergy() != null)
		{
			line.append(", run ").append(tick.getRunEnergy()).append('%');
		}
		line.append(", Yama targets ").append(tick.getYamaTarget());
		for (RecapHit hit : tick.getHits())
		{
			line.append(", hit ").append(hit.getAmount()).append(" (").append(hit.getSource().label()).append(')');
		}
		for (String item : tick.getConsumed())
		{
			line.append(", used ").append(item);
		}
		return line.toString();
	}

	private static String whereAndWhen(PhaseTimes times)
	{
		return times.last().map(span -> " in " + span.getPhase().label()).orElse("") + " at " + Formats.duration(times.getTotalTicks());
	}
}
