package com.yamareviewer.adapter.recording;

import com.yamareviewer.domain.event.Actor;
import com.yamareviewer.domain.event.ProtectionPrayer;
import com.yamareviewer.domain.event.TickState;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import javax.annotation.Nullable;
import net.runelite.api.Client;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;

/** Reads the end-of-tick state. Client thread only. Not final so tests can mock it. */
public class TickSampler
{
	private final Client client;
	private final ActorResolver actors;
	private final PositionReader positions;

	public TickSampler(Client client, ActorResolver actors, PositionReader positions)
	{
		this.client = client;
		this.actors = actors;
		this.positions = positions;
	}

	public TickState sample(int tick, @Nullable NPC yama, @Nullable Player partner)
	{
		Set<ProtectionPrayer> prayers = EnumSet.noneOf(ProtectionPrayer.class);
		if (client.getVarbitValue(VarbitID.PRAYER_PROTECTFROMMAGIC) == 1)
		{
			prayers.add(ProtectionPrayer.MAGIC);
		}
		if (client.getVarbitValue(VarbitID.PRAYER_PROTECTFROMMISSILES) == 1)
		{
			prayers.add(ProtectionPrayer.MISSILES);
		}
		if (client.getVarbitValue(VarbitID.PRAYER_PROTECTFROMMELEE) == 1)
		{
			prayers.add(ProtectionPrayer.MELEE);
		}

		Actor yamaTarget = yama == null || yama.getInteracting() == null ? null : actors.resolve(yama.getInteracting());
		Player self = client.getLocalPlayer();

		return new TickState(
			tick,
			Collections.unmodifiableSet(prayers),
			client.getBoostedSkillLevel(Skill.HITPOINTS),
			client.getBoostedSkillLevel(Skill.PRAYER),
			client.getVarpValue(VarPlayerID.SA_ENERGY) / 10,
			client.getEnergy() / 100,
			weaponId(),
			yamaTarget,
			self == null ? null : positions.position(self.getLocalLocation()),
			partner == null ? null : positions.position(partner.getLocalLocation()));
	}

	private int weaponId()
	{
		ItemContainer worn = client.getItemContainer(InventoryID.WORN);
		if (worn == null)
		{
			return -1;
		}
		Item weapon = worn.getItem(EquipmentInventorySlot.WEAPON.getSlotIdx());
		return weapon == null ? -1 : weapon.getId();
	}
}
