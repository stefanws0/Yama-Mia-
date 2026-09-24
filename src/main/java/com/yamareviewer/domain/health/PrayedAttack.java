package com.yamareviewer.domain.health;

import com.yamareviewer.domain.event.ProtectionPrayer;
import com.yamareviewer.domain.model.Style;
import com.yamareviewer.domain.review.Attack;
import java.util.Set;
import lombok.Value;

/** An attack aimed at SELF with a known style and damage, and SELF's protection prayers at its prayer-check tick. */
@Value
public class PrayedAttack
{
	Attack attack;
	int checkTick;
	Set<ProtectionPrayer> prayers;

	public int damage()
	{
		return attack.getDamage();
	}

	/** The prayer that blocks the attack's graphic style (spec 6.5: Magic for magic, Missiles for ranged). */
	public ProtectionPrayer correctPrayer()
	{
		return correctPrayer(attack.getStyle());
	}

	/** Spec 6.5 rule 1: the correct prayer was active at the check tick. */
	public boolean prayedCorrectly()
	{
		return prayers.contains(correctPrayer());
	}

	/** Protect from Magic or Missiles was on, so a big hit says something about magic versus ranged; Protect from Melee says nothing. */
	public boolean prayedAgainstMagicOrRanged()
	{
		return prayers.contains(ProtectionPrayer.MAGIC) || prayers.contains(ProtectionPrayer.MISSILES);
	}

	public static ProtectionPrayer correctPrayer(Style style)
	{
		return style == Style.MAGIC ? ProtectionPrayer.MAGIC : ProtectionPrayer.MISSILES;
	}
}
