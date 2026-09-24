package com.yamareviewer.domain.text;

import com.yamareviewer.domain.drain.SpecWeapon;
import com.yamareviewer.domain.drain.YamaStats;
import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.model.Phase;
import com.yamareviewer.domain.model.Style;
import com.yamareviewer.domain.review.AttackResult;
import com.yamareviewer.domain.review.CrashLine;
import com.yamareviewer.domain.review.CrashSummary;
import com.yamareviewer.domain.review.DrainStep;
import com.yamareviewer.domain.review.HiddenReason;
import com.yamareviewer.domain.review.HornUse;
import com.yamareviewer.domain.review.KillReview;
import com.yamareviewer.domain.review.Opener;
import com.yamareviewer.domain.review.PrayerOutcome;
import com.yamareviewer.domain.review.PrayerReview;
import com.yamareviewer.domain.review.Section;
import com.yamareviewer.domain.review.SpecOutcome;
import com.yamareviewer.domain.review.SpecResult;
import com.yamareviewer.domain.review.SpecSummary;
import com.yamareviewer.domain.review.TickLog;
import com.yamareviewer.domain.review.TickLogEntry;
import com.yamareviewer.domain.review.WaveHit;
import com.yamareviewer.domain.review.WaveSummary;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/** Every Part 3 line of chat, panel and clipboard (spec 7.2-7.4). Pure strings; never a player name. */
public final class P3Text
{
	static final String NA = "n/a";
	static final String ARROW = "→";

	private P3Text()
	{
	}

	/** Chat line 2: "P3 prayers 17/19 blocked (2 too early). Crash lines 8/9 dodged. Waves 5/6." */
	public static String prayerLine(KillReview review)
	{
		StringBuilder line = new StringBuilder("P3 prayers ");
		if (review.getPrayerReview().isOk())
		{
			PrayerReview prayers = review.getPrayerReview().value();
			line.append(prayers.blocked()).append('/').append(prayers.scored()).append(" blocked");
			String detail = mistakeDetail(prayers);
			if (!detail.isEmpty())
			{
				line.append(" (").append(detail).append(')');
			}
		}
		else
		{
			line.append(NA);
		}
		line.append(". Crash lines ");
		if (review.getCrashes().isOk())
		{
			CrashSummary crashes = review.getCrashes().value();
			line.append(crashes.dodged(Actor.SELF)).append('/').append(crashes.total(Actor.SELF)).append(" dodged");
		}
		else
		{
			line.append(NA);
		}
		line.append(". Waves ");
		if (review.getWaves().isOk())
		{
			WaveSummary waves = review.getWaves().value();
			line.append(waves.dodged(Actor.SELF)).append('/').append(waves.total(Actor.SELF));
		}
		else
		{
			line.append(NA);
		}
		return line.append('.').toString();
	}

	/** The text after "Specs: " in chat line 4: "Def 225→145 in P1 (3/3 Emberlight)", "none" or "n/a". */
	public static String specFragment(KillReview review)
	{
		if (!review.getSpecs().isOk())
		{
			return NA;
		}
		SpecSummary specs = review.getSpecs().value();
		if (specs.getSpecs().isEmpty())
		{
			return "none";
		}
		StringBuilder text = new StringBuilder("Def ").append(specs.getBase().getDefence()).append(ARROW).append(specs.getLowestDefence());
		if (specs.getLowestDefencePhase() != null)
		{
			text.append(" in ").append(phase(specs.getLowestDefencePhase()));
		}
		List<String> tallies = new ArrayList<>();
		for (SpecWeapon weapon : specs.weaponsUsed())
		{
			tallies.add(specs.landed(weapon) + "/" + specs.used(weapon) + " " + weapon.displayName());
		}
		if (specs.otherWeaponSpecs() > 0)
		{
			tallies.add(specs.otherWeaponSpecs() + " other");
		}
		if (!tallies.isEmpty())
		{
			text.append(" (").append(String.join(", ", tallies)).append(')');
		}
		return text.toString();
	}

	public static ViewSection opener(KillReview review)
	{
		return section("Opener", review.getOpener(), opener ->
		{
			List<String> lines = new ArrayList<>();
			String glyphs = "P2 glyphs: " + opener.getGlyphs().getFire() + " fire, " + opener.getGlyphs().getShadow() + " shadow: ";
			lines.add(glyphs + (opener.getExpectedStyle() == null ? "no expectation." : style(opener.getExpectedStyle()) + " opener expected."));
			if (opener.getFirstAttack() == null)
			{
				lines.add("Yama's first P3 attack: none seen.");
			}
			else
			{
				String verdict = opener.getAsExpected() == null ? "" : opener.getAsExpected() ? ", as expected" : ", not as expected";
				lines.add("Yama's first P3 attack: " + style(opener.getFirstAttack().getStyle()) + " on "
					+ who(opener.getFirstAttack().getTarget()) + verdict + ".");
			}
			lines.add(opener.getFirstOwnResult() == null
				? "Your first P3 attack: not scored."
				: "Your first P3 attack: " + labelOf(review, opener.getFirstOwnResult().getOutcome()) + ".");
			return lines;
		});
	}

	public static ViewSection prayerTimeline(KillReview review)
	{
		return section("P3 prayers", review.getPrayerReview(), prayers ->
		{
			List<String> lines = new ArrayList<>(prayers.getNotes());
			StringBuilder summary = new StringBuilder().append(prayers.blocked()).append('/').append(prayers.scored()).append(" blocked, ");
			if (prayers.mistakes() == 0)
			{
				summary.append("no mistakes");
			}
			else
			{
				summary.append(prayers.mistakes()).append(prayers.mistakes() == 1 ? " mistake (" : " mistakes (").append(mistakeDetail(prayers)).append(')');
			}
			if (prayers.getUnscored() > 0)
			{
				summary.append(", ").append(prayers.getUnscored()).append(" not scored");
			}
			lines.add(summary.append('.').toString());
			int number = 0;
			for (AttackResult result : prayers.getResults())
			{
				number++;
				String line = "#" + number + " t" + result.getAttack().getCastTick() + " " + style(result.getAttack().getStyle()) + ": "
					+ prayers.label(result.getOutcome());
				if (result.getSwitchTicks() != null)
				{
					line += " (switched after " + result.getSwitchTicks() + " ticks)";
				}
				lines.add(line);
			}
			return lines;
		});
	}

	public static ViewSection crashLines(KillReview review)
	{
		return section("Crash lines", review.getCrashes(), crashes ->
		{
			List<String> lines = new ArrayList<>();
			lines.add("You: " + crashes.dodged(Actor.SELF) + "/" + crashes.total(Actor.SELF) + " dodged, " + crashes.damage(Actor.SELF) + " damage.");
			if (crashes.total(Actor.PARTNER) > 0)
			{
				lines.add("Partner: " + crashes.dodged(Actor.PARTNER) + "/" + crashes.total(Actor.PARTNER) + " dodged, "
					+ crashes.damage(Actor.PARTNER) + " damage.");
			}
			for (CrashLine line : crashes.forPlayer(Actor.SELF))
			{
				lines.add("Set " + line.getSet() + " line " + line.getIndexInSet() + " t" + line.getTick() + ": "
					+ (line.isHit() ? "hit (" + line.getDamage() + ")" : "dodged"));
			}
			return lines;
		});
	}

	public static ViewSection waves(KillReview review)
	{
		return section("Waves", review.getWaves(), waves ->
		{
			List<String> lines = new ArrayList<>();
			String you = "You: " + waves.dodged(Actor.SELF) + "/" + waves.total(Actor.SELF) + " dodged, " + waves.damage(Actor.SELF) + " damage";
			if (waves.prayersDisabled(Actor.SELF) > 0)
			{
				you += ", prayers disabled " + waves.prayersDisabled(Actor.SELF) + "x";
			}
			lines.add(you + ".");
			if (waves.total(Actor.PARTNER) > 0)
			{
				lines.add("Partner: " + waves.dodged(Actor.PARTNER) + "/" + waves.total(Actor.PARTNER) + " dodged, "
					+ waves.damage(Actor.PARTNER) + " damage.");
			}
			for (WaveHit wave : waves.forPlayer(Actor.SELF))
			{
				String line = "t" + wave.getTick() + ": " + (wave.isHit() ? "hit (" + wave.getDamage() + ")" : "dodged");
				if (wave.isPrayersDisabled())
				{
					line += ", prayers disabled";
				}
				lines.add(line);
			}
			return lines;
		});
	}

	public static ViewSection specs(KillReview review)
	{
		return section("Specs and drains", review.getSpecs(), P3Text::specLines);
	}

	public static ViewSection tickLog(KillReview review)
	{
		return section("P3 tick log", review.getTickLog(), tickLog ->
		{
			List<String> lines = new ArrayList<>();
			lines.add(tickLog.gaps() == 0
				? "Fewer than two P3 attacks seen."
				: "Gaps of " + tickLog.getCycle() + " ticks: " + tickLog.gapsOnCycle() + "/" + tickLog.gaps() + ".");
			for (TickLogEntry entry : tickLog.getEntries())
			{
				String line = "t" + entry.getTick() + " " + style(entry.getStyle()) + " " + ARROW + " " + who(entry.getTarget());
				if (entry.getGap() != null)
				{
					line += " (+" + entry.getGap() + (entry.getAnnotations().isEmpty() ? "" : ": " + String.join(", ", entry.getAnnotations())) + ")";
				}
				lines.add(line);
			}
			return lines;
		});
	}

	public static String hiddenText(HiddenReason reason)
	{
		switch (reason)
		{
			case IDS_NOT_CAPTURED:
				return "Hidden: game IDs not captured";
			case CONTRACT:
				return "Not meaningful under this contract";
			case HEALTH_CHECK_FAILED:
				return "Hidden: a health check failed";
			case ERROR:
				return "Hidden: an error occurred while reviewing";
			default:
				return "Not applicable to this kill";
		}
	}

	/** The specs section lines; also the "spec efficiency" block of the clipboard text (spec 7.4). */
	static List<String> specLines(SpecSummary specs)
	{
		List<String> lines = new ArrayList<>();
		YamaStats base = specs.getBase();
		if (specs.defenceDrained() == 0)
		{
			lines.add("Defence " + base.getDefence() + ": nothing drained (modelled).");
		}
		else
		{
			lines.add("Defence " + base.getDefence() + ARROW + specs.getLowestDefence() + " (lowest, in " + phase(specs.getLowestDefencePhase()) + "), "
				+ specs.defenceDrained() + " of " + specs.maxDefenceDrain() + " drained (modelled).");
		}
		int lowestMagic = base.getMagic();
		for (DrainStep step : specs.getDrains())
		{
			lowestMagic = Math.min(lowestMagic, step.getAfter().getMagic());
		}
		if (lowestMagic < base.getMagic())
		{
			lines.add("Magic " + base.getMagic() + ARROW + lowestMagic + " (modelled).");
		}
		if (specs.getFinalStats().getMagicDefenceBonusDrained() > 0)
		{
			lines.add("Magic defence bonus drained: " + specs.getFinalStats().getMagicDefenceBonusDrained() + ".");
		}
		for (SpecWeapon weapon : specs.weaponsUsed())
		{
			String line = weapon.displayName() + ": " + specs.landed(weapon) + "/" + specs.used(weapon) + " landed";
			String average = averageDamage(specs, weapon);
			lines.add(average.isEmpty() ? line : line + ", " + average);
		}
		if (specs.otherWeaponSpecs() > 0)
		{
			int damage = 0;
			for (SpecResult spec : specs.getSpecs())
			{
				damage += spec.isOtherWeapon() && spec.getDamage() != null ? spec.getDamage() : 0;
			}
			lines.add("Other weapon: " + specs.otherWeaponSpecs() + (specs.otherWeaponSpecs() == 1 ? " spec, " : " specs, ") + damage + " damage.");
		}
		if (specs.defenceDrained() > 0 && specs.ownEnergyUsedOnDrains() > 0)
		{
			lines.add(String.format(Locale.ROOT, "Spec energy per Defence point drained: %.1f%%.",
				(double) specs.ownEnergyUsedOnDrains() / specs.defenceDrained()));
		}
		for (HornUse horn : specs.getHorns())
		{
			String line = "Soulflame horn t" + horn.getTick() + " (" + who(horn.getUser()) + "): ";
			if (horn.isUnused())
			{
				line += "unused.";
			}
			else
			{
				SpecResult assisted = horn.getAssistedSpec();
				line += "assisted " + assisted.weaponName() + " at t" + assisted.getTick() + ", " + outcome(assisted.getOutcome()) + ".";
			}
			lines.add(line);
		}
		for (SpecResult spec : specs.getSpecs())
		{
			if (spec.getHpRestored() != null)
			{
				lines.add("t" + spec.getTick() + " Saradomin godsword restored " + spec.getHpRestored() + " HP and " + spec.getPrayerRestored() + " prayer points.");
			}
		}
		for (DrainStep step : specs.getDrains())
		{
			List<String> changes = new ArrayList<>();
			if (step.getBefore().getDefence() != step.getAfter().getDefence())
			{
				changes.add("Def " + step.getBefore().getDefence() + ARROW + step.getAfter().getDefence());
			}
			if (step.getBefore().getMagic() != step.getAfter().getMagic())
			{
				changes.add("Magic " + step.getBefore().getMagic() + ARROW + step.getAfter().getMagic());
			}
			if (step.getBefore().getMagicDefenceBonusDrained() != step.getAfter().getMagicDefenceBonusDrained())
			{
				changes.add("magic defence bonus " + step.getBefore().getMagicDefenceBonusDrained() + ARROW + step.getAfter().getMagicDefenceBonusDrained());
			}
			lines.add("t" + step.getTick() + " " + step.getCause() + ": " + (changes.isEmpty() ? "no change" : String.join(", ", changes)));
		}
		return lines;
	}

	private static String averageDamage(SpecSummary specs, SpecWeapon weapon)
	{
		int total = 0;
		int count = 0;
		for (SpecResult spec : specs.getSpecs())
		{
			if (spec.getWeapon() == weapon && spec.getDamage() != null)
			{
				total += spec.getDamage();
				count++;
			}
		}
		return count == 0 ? "" : (total / count) + " avg damage";
	}

	private static String mistakeDetail(PrayerReview prayers)
	{
		List<String> parts = new ArrayList<>();
		for (PrayerOutcome outcome : List.of(PrayerOutcome.TOO_EARLY, PrayerOutcome.LATE, PrayerOutcome.LOST_ALTERNATION, PrayerOutcome.NO_PRAYER))
		{
			if (prayers.count(outcome) > 0)
			{
				parts.add(prayers.count(outcome) + " " + prayers.label(outcome));
			}
		}
		return String.join(", ", parts);
	}

	private static String labelOf(KillReview review, PrayerOutcome outcome)
	{
		return review.getPrayerReview().isOk()
			? review.getPrayerReview().value().label(outcome)
			: new PrayerReview(List.of(), 0, "lost alternation", List.of()).label(outcome);
	}

	private static <T> ViewSection section(String title, Section<T> section, Function<T, List<String>> lines)
	{
		if (section.isOk())
		{
			return new ViewSection(title, lines.apply(section.value()), null);
		}
		return new ViewSection(title, List.of(), hiddenText(section.hiddenReason().orElse(HiddenReason.NOT_APPLICABLE)));
	}

	static String who(Actor actor)
	{
		if (Actor.SELF.equals(actor))
		{
			return "you";
		}
		return Actor.PARTNER.equals(actor) ? "partner" : "unknown";
	}

	static String style(Style style)
	{
		if (style == null)
		{
			return "unknown style";
		}
		return style == Style.MAGIC ? "magic" : "ranged";
	}

	static String phase(Phase phase)
	{
		if (phase == null)
		{
			return "an unknown phase";
		}
		switch (phase)
		{
			case P1:
				return "P1";
			case JUDGE_1:
				return "Judge 1";
			case P2:
				return "P2";
			case JUDGE_2:
				return "Judge 2";
			default:
				return "P3";
		}
	}

	private static String outcome(SpecOutcome outcome)
	{
		switch (outcome)
		{
			case LANDED:
				return "landed";
			case MISSED:
				return "missed";
			default:
				return "result unknown";
		}
	}
}
