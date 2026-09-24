package com.yamareviewer.adapter.ids;

import com.yamareviewer.domain.ids.IdRegistry;
import com.yamareviewer.domain.ids.Role;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import net.runelite.api.gameval.AnimationID;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.NpcID;
import net.runelite.api.gameval.ObjectID;
import net.runelite.api.gameval.SpotanimID;
import net.runelite.api.gameval.VarbitID;

/**
 * The only place with Yama-specific game IDs. Uses RuneLite's gameval constants, so a RuneLite update
 * that regenerates them after a game update often fixes a renumbering without a plugin update.
 * Mappings marked unconfirmed in spec 5.5 are confirmed by the golden tests of the logging kills.
 * Still to capture: JUDGE_FIRE_SURGE, CRASH_FIREBALL, SPEC_PURGING_STAFF, SHADOW_POOL.
 */
public final class BuiltInIds
{
	/** Yama's Domain, from Yama Utilities; there is no gameval constant for regions. */
	private static final int YAMAS_DOMAIN_REGION = 6045;

	private BuiltInIds()
	{
	}

	public static IdRegistry registry()
	{
		Map<Role, Set<Integer>> ids = new EnumMap<>(Role.class);

		ids.put(Role.YAMA, Set.of(NpcID.YAMA));
		ids.put(Role.JUDGE, Set.of(NpcID.YAMA_JUDGE_OF_YAMA));
		ids.put(Role.VOID_FLARE, Set.of(NpcID.YAMA_VOIDFLARE));
		ids.put(Role.VOICE_OF_YAMA, Set.of(NpcID.VOICE_OF_YAMA_1OP, NpcID.VOICE_OF_YAMA_2OP, NpcID.VOICE_OF_YAMA_3OP));
		ids.put(Role.METEOR_NPC, Set.of(NpcID.YAMA_METEOR_NPC));

		ids.put(Role.YAMAS_DOMAIN, Set.of(YAMAS_DOMAIN_REGION));

		ids.put(Role.PHASE_VARBIT, Set.of(VarbitID.YAMA_TRANSITION_PHASE));

		ids.put(Role.CONTRACT_NAME_WIDGET, Set.of(InterfaceID.YamaContractFight.CONTRACT_NAME));

		ids.put(Role.CONTRACT_ITEM_FORFEIT_BREATH, Set.of(ItemID.YAMA_BINDING_CONTRACT));
		ids.put(Role.CONTRACT_ITEM_GLYPHIC_ATTENUATION, Set.of(ItemID.YAMA_SPECIAL_CONTRACT));
		ids.put(Role.CONTRACT_ITEM_SENSORY_CLOUDING, Set.of(ItemID.YAMA_SPELL_CONTRACT));
		ids.put(Role.CONTRACT_ITEM_DIVINE_SEVERANCE, Set.of(ItemID.YAMA_HEAVYRANGED_CONTRACT));
		ids.put(Role.CONTRACT_ITEM_BLOODIED_BLOWS, Set.of(ItemID.YAMA_2H_CONTRACT));
		ids.put(Role.CONTRACT_ITEM_FAMILIAR, Set.of(ItemID.YAMA_PET_CONTRACT));
		ids.put(Role.CONTRACT_ITEM_CATALYST, Set.of(ItemID.YAMA_CATALYST_CONTRACT));
		ids.put(Role.CONTRACT_ITEM_WORM, Set.of(ItemID.YAMA_WORM_CONTRACT));
		ids.put(Role.CONTRACT_ITEM_SHARD, Set.of(ItemID.YAMA_SHARD_CONTRACT));
		ids.put(Role.CONTRACT_ITEM_OATHPLATE, Set.of(ItemID.YAMA_ARMOUR_CONTRACT));
		ids.put(Role.CONTRACT_ITEM_HARMONY, Set.of(ItemID.YAMA_HORN_CONTRACT));
		ids.put(Role.WEAPON_EMBERLIGHT, Set.of(ItemID.EMBERLIGHT));
		ids.put(Role.WEAPON_ELDER_MAUL, Set.of(ItemID.ELDER_MAUL, ItemID.ELDER_MAUL_ORNAMENT));
		ids.put(Role.WEAPON_DRAGON_WARHAMMER, Set.of(ItemID.DRAGON_WARHAMMER, ItemID.DRAGON_WARHAMMER_ORNAMENT));
		ids.put(Role.WEAPON_BANDOS_GODSWORD, Set.of(ItemID.BGS, ItemID.BGSG));
		ids.put(Role.WEAPON_ACCURSED_SCEPTRE, Set.of(ItemID.WILD_CAVE_ACCURSED_CHARGED, ItemID.WILD_CAVE_ACCURSED_CHARGED_RECOL));
		ids.put(Role.WEAPON_EYE_OF_AYAK, Set.of(ItemID.EYE_OF_AYAK));
		ids.put(Role.WEAPON_SOULFLAME_HORN, Set.of(ItemID.SOULFLAME_HORN));
		ids.put(Role.WEAPON_PURGING_STAFF, Set.of(ItemID.PURGING_STAFF));
		ids.put(Role.WEAPON_SARADOMIN_GODSWORD, Set.of(ItemID.SGS, ItemID.SGSG));

		// ObjectID extends the package-private ObjectID1, which declares these constants.
		ids.put(Role.GLYPH_FIRE, Set.of(ObjectID.FLOORKIT_SUMMONING03_FULL02));
		ids.put(Role.GLYPH_SHADOW, Set.of(ObjectID.FLOORKIT_SUMMONING03_FULL01));

		ids.put(Role.YAMA_STANDARD_ATTACK, Set.of(AnimationID.NPC_YAMA01_MAGIC01));
		ids.put(Role.YAMA_MELEE, Set.of(AnimationID.NPC_YAMA01_MELEE01));
		ids.put(Role.YAMA_FLARE_SUMMON, Set.of(AnimationID.NPC_YAMA_SUMMON01));
		ids.put(Role.SHADOW_STOMP, Set.of(AnimationID.NPC_YAMA01_STOMP01));
		ids.put(Role.FLARE_EXPLODE, Set.of(AnimationID.NPC_VOIDFLARE_EXPLODE));
		ids.put(Role.FLARE_DEATH, Set.of(AnimationID.NPC_VOIDFLARE_DEATH));
		ids.put(Role.SPEC_EMBERLIGHT, Set.of(AnimationID.HUMAN_WEAPON_EMBERLIGHT_01_SPEC));
		ids.put(Role.SPEC_ELDER_MAUL, Set.of(AnimationID.HUMAN_ELDER_MAUL_SPEC));
		ids.put(Role.SPEC_DRAGON_WARHAMMER, Set.of(AnimationID.DRAGON_WARHAMMER_SA_PLAYER));
		ids.put(Role.SPEC_BANDOS_GODSWORD, Set.of(AnimationID.BGS_SPECIAL_PLAYER, AnimationID.BGS_SPECIAL_ORNATE_PLAYER));
		ids.put(Role.SPEC_ACCURSED_SCEPTRE, Set.of(AnimationID.HUMAN_SPECIAL_ACCURSED));
		ids.put(Role.SPEC_EYE_OF_AYAK, Set.of(AnimationID.HUMAN_EYE_OF_AYAK_SPECIAL));
		ids.put(Role.SPEC_SOULFLAME_HORN, Set.of(AnimationID.SOULFLAME_HORN_BLOW_01, AnimationID.SOULFLAME_HORN_BLOW_02,
			AnimationID.SOULFLAME_HORN_BLOW_03, AnimationID.SOULFLAME_HORN_BLOW_03_NO_FIRE));
		ids.put(Role.SPEC_SARADOMIN_GODSWORD, Set.of(AnimationID.SGS_SPECIAL_PLAYER, AnimationID.SGS_SPECIAL_ORNATE_PLAYER));

		ids.put(Role.YAMA_CAST_MAGIC, Set.of(SpotanimID.VFX_NPC_YAMA_MAGIC_FIRE_SPOTANIM01));
		ids.put(Role.YAMA_CAST_RANGED, Set.of(SpotanimID.VFX_NPC_YAMA_MAGIC_SHADOW_SPOTANIM01));
		ids.put(Role.IMPACT_MAGIC, Set.of(SpotanimID.VFX_PLAYER_YAMA_MAGIC_FIRE_IMPACT01));
		ids.put(Role.IMPACT_RANGED, Set.of(SpotanimID.VFX_PLAYER_YAMA_MAGIC_SHADOW_IMPACT01));
		ids.put(Role.CRASH_IMPACT, Set.of(SpotanimID.VFX_PLAYER_YAMA_FALLING_ROCK_IMPACT01));
		ids.put(Role.SHADOW_WAVE, Set.of(SpotanimID.VFX_SHADOW_WALL_SMALL, SpotanimID.VFX_SHADOW_WALL_01,
			SpotanimID.VFX_SHADOW_WALL_02, SpotanimID.VFX_SHADOW_WALL_03));
		ids.put(Role.FIRE_STREAK, Set.of(SpotanimID.VFX_FIRE_WALL_01, SpotanimID.VFX_FIRE_WALL_02, SpotanimID.VFX_FIRE_WALL_03));
		ids.put(Role.FIRE_ATTACK, Set.of(SpotanimID.VFX_YAMA_FLAMING_ROCK_SPOTANIM_01,
			SpotanimID.VFX_YAMA_FLAMING_ROCK_PROJECTILE_01, SpotanimID.VFX_YAMA_FLAMING_ROCK_IMPACT_01));
		ids.put(Role.METEOR_STRIKE, Set.of(SpotanimID.VFX_YAMA_METEOR_SPOTANIM01, SpotanimID.VFX_YAMA_METEOR_PROJECTILE_01,
			SpotanimID.VFX_YAMA_METEOR_PROJECTILE_02, SpotanimID.VFX_YAMA_METEOR_PROJECTILE_03));
		ids.put(Role.GLYPH_PROTECTION, Set.of(SpotanimID.VFX_YAMA_FIRE_IMMUNITY, SpotanimID.VFX_YAMA_SHADOW_IMMUNITY));
		ids.put(Role.PHASE_TRANSITION_GRAPHIC, Set.of(SpotanimID.VFX_YAMA_PORTAL_SHADOW_SPOTANIM01));
		ids.put(Role.FLARE_HEAL, Set.of(SpotanimID.VFX_VOIDFLARE_EXPLODE_YAMA_IMPACT_RED, SpotanimID.VFX_VOIDFLARE_EXPLODE_YAMA_IMPACT_BLUE));
		ids.put(Role.FLARE_HIT, Set.of(SpotanimID.VFX_VOIDFLARE_HUMAN_IMPACT_RED, SpotanimID.VFX_VOIDFLARE_HUMAN_IMPACT_BLUE));

		Map<Role, Set<String>> texts = Map.of(
			Role.PHASE_TRANSITION_TEXT, Set.of("Begone", "You bore me.", "Enough."),
			Role.PRAYER_DISABLED_MESSAGE, Set.of("You've been injured and can't use protection prayers!"),
			Role.GLYPH_CONJURE_MESSAGE, Set.of("Yama conjures"));

		return new IdRegistry(ids, texts);
	}
}
