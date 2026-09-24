package com.yamareviewer.domain.drain;

import com.yamareviewer.domain.ids.IdRegistry;
import com.yamareviewer.domain.ids.Role;
import java.util.Optional;

/** Every special-attack weapon the review knows: its item role, its animation role and its drain. */
public enum SpecWeapon
{
	EMBERLIGHT(Role.WEAPON_EMBERLIGHT, Role.SPEC_EMBERLIGHT, "Emberlight", true, new EmberlightDrain()),
	ELDER_MAUL(Role.WEAPON_ELDER_MAUL, Role.SPEC_ELDER_MAUL, "Elder maul", true, new ElderMaulDrain()),
	DRAGON_WARHAMMER(Role.WEAPON_DRAGON_WARHAMMER, Role.SPEC_DRAGON_WARHAMMER, "Dragon warhammer", true, new DragonWarhammerDrain()),
	BANDOS_GODSWORD(Role.WEAPON_BANDOS_GODSWORD, Role.SPEC_BANDOS_GODSWORD, "Bandos godsword", true, new BandosGodswordDrain()),
	ACCURSED_SCEPTRE(Role.WEAPON_ACCURSED_SCEPTRE, Role.SPEC_ACCURSED_SCEPTRE, "Accursed sceptre", false, new AccursedSceptreDrain()),
	EYE_OF_AYAK(Role.WEAPON_EYE_OF_AYAK, Role.SPEC_EYE_OF_AYAK, "Eye of Ayak", false, new EyeOfAyakDrain()),
	SOULFLAME_HORN(Role.WEAPON_SOULFLAME_HORN, Role.SPEC_SOULFLAME_HORN, "Soulflame horn", false, new NoDrain()),
	PURGING_STAFF(Role.WEAPON_PURGING_STAFF, Role.SPEC_PURGING_STAFF, "Purging staff", false, new NoDrain()),
	SARADOMIN_GODSWORD(Role.WEAPON_SARADOMIN_GODSWORD, Role.SPEC_SARADOMIN_GODSWORD, "Saradomin godsword", true, new NoDrain());

	private final Role weaponRole;
	private final Role specRole;
	private final String displayName;
	private final boolean melee;
	private final DrainRule drainRule;

	SpecWeapon(Role weaponRole, Role specRole, String displayName, boolean melee, DrainRule drainRule)
	{
		this.weaponRole = weaponRole;
		this.specRole = specRole;
		this.displayName = displayName;
		this.melee = melee;
		this.drainRule = drainRule;
	}

	public Role weaponRole()
	{
		return weaponRole;
	}

	public Role specRole()
	{
		return specRole;
	}

	public String displayName()
	{
		return displayName;
	}

	/** A melee spec can be assisted by a soulflame horn (spec 6.9). */
	public boolean isMelee()
	{
		return melee;
	}

	/** False for the weapons whose spec drains nothing. */
	public boolean drains()
	{
		return !(drainRule instanceof NoDrain);
	}

	public DrainRule drainRule()
	{
		return drainRule;
	}

	public static Optional<SpecWeapon> ofWeapon(IdRegistry ids, int itemId)
	{
		for (SpecWeapon weapon : values())
		{
			if (ids.is(weapon.weaponRole, itemId))
			{
				return Optional.of(weapon);
			}
		}
		return Optional.empty();
	}

	public static Optional<SpecWeapon> ofAnimation(IdRegistry ids, int animationId)
	{
		for (SpecWeapon weapon : values())
		{
			if (ids.is(weapon.specRole, animationId))
			{
				return Optional.of(weapon);
			}
		}
		return Optional.empty();
	}
}
