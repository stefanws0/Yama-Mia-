package com.yamareviewer.domain.review;

import com.yamareviewer.domain.drain.SpecWeapon;
import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.model.Phase;
import lombok.Value;

/** One special attack (spec 6.9). */
@Value
public class SpecResult
{
	int tick;
	/** Null when the phases are unknown. */
	Phase phase;
	/** SELF or PARTNER. */
	Actor user;
	/** Null for an OTHER_WEAPON spec: a spec-energy drop with a weapon the review doesn't know. */
	SpecWeapon weapon;
	/** False when the drop had no matching SPEC_* animation on the same or the previous tick. */
	boolean animationSeen;
	/** Yama, or the flare for a purging staff spec; null when no result hitsplat was found. */
	Actor target;
	SpecOutcome outcome;
	/** The result hitsplat's amount; null when UNKNOWN. */
	Integer damage;
	/** Percent of spec energy the drop consumed; 0 for partner specs and for a refunded purging staff spec. */
	int energyUsed;
	/** Saradomin godsword only: hitpoints restored by the hit; null otherwise. */
	Integer hpRestored;
	/** Saradomin godsword only: prayer points restored by the hit; null otherwise. */
	Integer prayerRestored;

	public boolean isOwn()
	{
		return Actor.SELF.equals(user);
	}

	public boolean isOtherWeapon()
	{
		return weapon == null;
	}

	public String weaponName()
	{
		return weapon == null ? "Other weapon" : weapon.displayName();
	}
}
