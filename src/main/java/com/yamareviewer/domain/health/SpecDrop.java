package com.yamareviewer.domain.health;

import com.yamareviewer.domain.ids.Role;
import lombok.Value;

/** Spec energy going down between two consecutive TickStates (spec 6.9). */
@Value
public class SpecDrop
{
	int tick;
	int energyBefore;
	int energyAfter;
	int weaponId;
	/** The WEAPON_* role of the wielded weapon; null for any other weapon (an OTHER_WEAPON spec). */
	Role weaponRole;
	/** A SPEC_* animation played on SELF on this tick or the tick before. */
	boolean matched;

	/** The SpecsMatched failure condition: a known spec weapon, but no spec animation. */
	public boolean unmatchedKnownWeapon()
	{
		return weaponRole != null && !matched;
	}
}
