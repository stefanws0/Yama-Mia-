package com.yamareviewer.domain.model;

import com.yamareviewer.domain.ids.Role;
import java.util.Locale;
import java.util.Optional;

/** The contracts of spec 6.3.1, plus NONE and the unknown contract detected from the attack cycle. */
public enum Contract
{
	NONE(null, null),
	UNKNOWN_CONTRACT(null, null),
	FORFEIT_BREATH("Forfeit Breath", Role.CONTRACT_ITEM_FORFEIT_BREATH),
	GLYPHIC_ATTENUATION("Glyphic Attenuation", Role.CONTRACT_ITEM_GLYPHIC_ATTENUATION),
	SENSORY_CLOUDING("Sensory Clouding", Role.CONTRACT_ITEM_SENSORY_CLOUDING),
	DIVINE_SEVERANCE("Divine Severance", Role.CONTRACT_ITEM_DIVINE_SEVERANCE),
	BLOODIED_BLOWS("Bloodied Blows", Role.CONTRACT_ITEM_BLOODIED_BLOWS),
	FAMILIAR_ACQUISITION("Familiar Acquisition", Role.CONTRACT_ITEM_FAMILIAR),
	CATALYST_ACQUISITION("Catalyst Acquisition", Role.CONTRACT_ITEM_CATALYST),
	WORM_ACQUISITION("Worm Acquisition", Role.CONTRACT_ITEM_WORM),
	SHARD_ACQUISITION("Shard Acquisition", Role.CONTRACT_ITEM_SHARD),
	OATHPLATE_ACQUISITION("Oathplate Acquisition", Role.CONTRACT_ITEM_OATHPLATE),
	HARMONY_ACQUISITION("Harmony Acquisition", Role.CONTRACT_ITEM_HARMONY);

	private final String shortName;
	private final Role itemRole;

	Contract(String shortName, Role itemRole)
	{
		this.shortName = shortName;
		this.itemRole = itemRole;
	}

	/** Null for NONE and UNKNOWN_CONTRACT. */
	public String shortName()
	{
		return shortName;
	}

	/** Null for NONE and UNKNOWN_CONTRACT. */
	public Role itemRole()
	{
		return itemRole;
	}

	/** False only for NONE. */
	public boolean isContract()
	{
		return this != NONE;
	}

	public String displayName()
	{
		if (this == NONE)
		{
			return "No contract";
		}
		if (this == UNKNOWN_CONTRACT)
		{
			return "Unknown contract";
		}
		return "Contract of " + shortName;
	}

	/** Case-insensitive: the first contract whose shortName occurs in the text (tags already removed). */
	public static Optional<Contract> fromWidgetText(String text)
	{
		if (text == null)
		{
			return Optional.empty();
		}
		String lower = text.toLowerCase(Locale.ROOT);
		for (Contract contract : values())
		{
			if (contract.shortName != null && lower.contains(contract.shortName.toLowerCase(Locale.ROOT)))
			{
				return Optional.of(contract);
			}
		}
		return Optional.empty();
	}
}
