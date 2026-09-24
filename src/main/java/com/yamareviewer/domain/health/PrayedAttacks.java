package com.yamareviewer.domain.health;

import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.TickState;
import com.yamareviewer.domain.ids.Rules;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.model.Phase;
import com.yamareviewer.domain.review.Attack;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Attacks with behaviour evidence (spec 6.4): aimed at SELF, style and damage known, with SELF's prayers at the check tick. */
public final class PrayedAttacks
{
	private PrayedAttacks()
	{
	}

	/** @param phase only attacks of this phase, or every phase when null */
	public static List<PrayedAttack> onSelf(List<Attack> attacks, KillLog log, Rules rules, Phase phase)
	{
		TickStateIndex states = TickStateIndex.of(log);
		List<PrayedAttack> result = new ArrayList<>();
		for (Attack attack : attacks)
		{
			if (!Actor.SELF.equals(attack.getTarget()) || attack.getStyle() == null || attack.getDamage() == null)
			{
				continue;
			}
			if (phase != null && attack.getPhase() != phase)
			{
				continue;
			}
			int tick = PrayerCheckTick.of(attack, rules);
			Optional<TickState> state = states.at(tick);
			if (state.isPresent())
			{
				result.add(new PrayedAttack(attack, tick, state.get().getPrayers()));
			}
		}
		return List.copyOf(result);
	}
}
