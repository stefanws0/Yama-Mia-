package com.yamareviewer.domain.health;

import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.AnimationObserved;
import com.yamareviewer.domain.event.TickState;
import com.yamareviewer.domain.ids.IdRegistry;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.ids.RoleKind;
import com.yamareviewer.domain.model.KillLog;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Finds every spec-energy drop of SELF and whether a SPEC_* animation explains it (spec 6.9). */
public final class SpecEnergyDrops
{
	public static final Set<Role> SPEC_ROLES = Collections.unmodifiableSet(EnumSet.of(
		Role.SPEC_EMBERLIGHT, Role.SPEC_ELDER_MAUL, Role.SPEC_DRAGON_WARHAMMER, Role.SPEC_BANDOS_GODSWORD,
		Role.SPEC_ACCURSED_SCEPTRE, Role.SPEC_EYE_OF_AYAK, Role.SPEC_SOULFLAME_HORN, Role.SPEC_PURGING_STAFF,
		Role.SPEC_SARADOMIN_GODSWORD));

	public static final Set<Role> WEAPON_ROLES = Collections.unmodifiableSet(EnumSet.of(
		Role.WEAPON_EMBERLIGHT, Role.WEAPON_ELDER_MAUL, Role.WEAPON_DRAGON_WARHAMMER, Role.WEAPON_BANDOS_GODSWORD,
		Role.WEAPON_ACCURSED_SCEPTRE, Role.WEAPON_EYE_OF_AYAK, Role.WEAPON_SOULFLAME_HORN, Role.WEAPON_PURGING_STAFF,
		Role.WEAPON_SARADOMIN_GODSWORD));

	private SpecEnergyDrops()
	{
	}

	public static List<SpecDrop> find(KillLog log, IdRegistry ids)
	{
		Set<Integer> specAnimationTicks = new HashSet<>();
		for (AnimationObserved animation : log.eventsOf(AnimationObserved.class))
		{
			if (Actor.SELF.equals(animation.getActor())
				&& ids.roleOf(RoleKind.ANIMATION, animation.getAnimationId()).filter(SPEC_ROLES::contains).isPresent())
			{
				specAnimationTicks.add(animation.getTick());
			}
		}
		List<TickState> states = log.eventsOf(TickState.class);
		List<SpecDrop> drops = new ArrayList<>();
		for (int i = 1; i < states.size(); i++)
		{
			TickState before = states.get(i - 1);
			TickState after = states.get(i);
			if (after.getSpecEnergy() >= before.getSpecEnergy())
			{
				continue;
			}
			int weaponId = after.getWeaponId() != -1 ? after.getWeaponId() : before.getWeaponId();
			Role weaponRole = weaponRole(ids, after.getWeaponId()).or(() -> weaponRole(ids, before.getWeaponId())).orElse(null);
			boolean matched = specAnimationTicks.contains(after.getTick()) || specAnimationTicks.contains(after.getTick() - 1);
			drops.add(new SpecDrop(after.getTick(), before.getSpecEnergy(), after.getSpecEnergy(), weaponId, weaponRole, matched));
		}
		return List.copyOf(drops);
	}

	private static Optional<Role> weaponRole(IdRegistry ids, int itemId)
	{
		return itemId < 0 ? Optional.empty() : ids.roleOf(RoleKind.ITEM, itemId).filter(WEAPON_ROLES::contains);
	}
}
