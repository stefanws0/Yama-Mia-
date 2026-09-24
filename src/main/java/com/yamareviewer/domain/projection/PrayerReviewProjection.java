package com.yamareviewer.domain.projection;

import com.yamareviewer.domain.contract.ContractRules;
import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.GameMessageObserved;
import com.yamareviewer.domain.event.ProtectionPrayer;
import com.yamareviewer.domain.event.TickState;
import com.yamareviewer.domain.ids.IdRegistry;
import com.yamareviewer.domain.ids.PrayerCheck;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.ids.Rules;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.model.Phase;
import com.yamareviewer.domain.outcome.OutcomeRules;
import com.yamareviewer.domain.outcome.PrayerContext;
import com.yamareviewer.domain.review.Attack;
import com.yamareviewer.domain.review.AttackResult;
import com.yamareviewer.domain.review.AttackTimeline;
import com.yamareviewer.domain.review.HiddenReason;
import com.yamareviewer.domain.review.PhaseTimes;
import com.yamareviewer.domain.review.PrayerOutcome;
import com.yamareviewer.domain.review.PrayerReview;
import com.yamareviewer.domain.review.Section;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Scores every P3 attack aimed at you with the outcome rules of spec 6.5. Attacks on the partner are not
 * scored but still move the switch-timing origin, because alternation is global.
 */
public final class PrayerReviewProjection implements Projection<PrayerReview>
{
	static final String LOST_ALTERNATION_LABEL = "lost alternation";
	static final String WRONG_PRAYER_LABEL = "wrong prayer";
	static final String SHARD_NOTE = "Under Shard Acquisition Yama's attack styles are random, so a wrong prayer is reported as a wrong prayer, not a lost alternation.";
	static final String AUTO_OFF_NOTE = "Under Divine Severance protection prayers switch off %d ticks after activation: a correct prayer switched on 3 or more ticks before the check tick is already off again and scores too early.";

	@Override
	public SectionKey<PrayerReview> key()
	{
		return Sections.PRAYER_REVIEW;
	}

	@Override
	public Set<Role> requiredRoles()
	{
		return EnumSet.of(Role.YAMA_CAST_MAGIC, Role.YAMA_CAST_RANGED);
	}

	@Override
	public Section<PrayerReview> project(KillLog log, ProjectionContext context)
	{
		Section<PhaseTimes> phases = context.section(Sections.PHASES);
		Section<AttackTimeline> attacks = context.section(Sections.ATTACKS);
		Optional<HiddenReason> hidden = Upstream.hidden(phases, attacks);
		if (hidden.isPresent())
		{
			return Section.hidden(hidden.get());
		}
		Rules rules = context.rules();
		ContractRules contract = context.contractRules();
		Map<Integer, Set<ProtectionPrayer>> prayersByTick = prayersByTick(log);
		List<Integer> disabledTicks = prayerDisabledTicks(log, context.ids());

		List<AttackResult> results = new ArrayList<>();
		int unscored = 0;
		Integer previousCheck = null;
		for (Attack attack : attacks.value().inPhase(Phase.P3))
		{
			int check = checkTick(attack, rules);
			if (Actor.SELF.equals(attack.getTarget()))
			{
				if (attack.getStyle() == null)
				{
					unscored++;
				}
				else
				{
					PrayerContext prayerContext = new PrayerContext(check, PrayerContext.correctFor(attack.getStyle()),
						prayersByTick, disabledTicks, rules.getWaveDisableWindow());
					PrayerOutcome outcome = OutcomeRules.evaluate(prayerContext);
					results.add(new AttackResult(attack, check, outcome, switchTicks(prayerContext, outcome, previousCheck)));
				}
			}
			previousCheck = check;
		}

		List<String> notes = new ArrayList<>();
		if (contract.isRandomAttackStyles())
		{
			notes.add(SHARD_NOTE);
		}
		if (contract.getPrayerAutoOffTicks() > 0)
		{
			notes.add(String.format(AUTO_OFF_NOTE, contract.getPrayerAutoOffTicks()));
		}
		String label = contract.isRandomAttackStyles() ? WRONG_PRAYER_LABEL : LOST_ALTERNATION_LABEL;
		return Section.ok(new PrayerReview(results, unscored, label, notes));
	}

	/** The prayer-check tick L: the cast tick, or under HITSPLAT the landing tick (the cast tick when none), plus the offset. */
	static int checkTick(Attack attack, Rules rules)
	{
		int base = rules.getPrayerCheck() == PrayerCheck.HITSPLAT && attack.getLandingTick() != null
			? attack.getLandingTick()
			: attack.getCastTick();
		return base + rules.getPrayerCheckOffset();
	}

	private static Integer switchTicks(PrayerContext context, PrayerOutcome outcome, Integer previousCheck)
	{
		if (previousCheck == null || outcome == PrayerOutcome.NO_PRAYER || outcome == PrayerOutcome.PRAYER_DISABLED)
		{
			return null;
		}
		for (int tick = previousCheck; tick <= context.getCheckTick() + 1; tick++)
		{
			if (context.correctAt(tick))
			{
				return tick - previousCheck;
			}
		}
		return null;
	}

	private static Map<Integer, Set<ProtectionPrayer>> prayersByTick(KillLog log)
	{
		Map<Integer, Set<ProtectionPrayer>> prayers = new HashMap<>();
		for (TickState state : log.eventsOf(TickState.class))
		{
			prayers.put(state.getTick(), state.getPrayers());
		}
		return prayers;
	}

	private static List<Integer> prayerDisabledTicks(KillLog log, IdRegistry ids)
	{
		List<Integer> ticks = new ArrayList<>();
		for (GameMessageObserved message : log.eventsOf(GameMessageObserved.class))
		{
			if (ids.matchesText(Role.PRAYER_DISABLED_MESSAGE, message.getText()))
			{
				ticks.add(message.getTick());
			}
		}
		return ticks;
	}
}
