package com.yamareviewer.domain.drain;

import com.yamareviewer.domain.contract.ContractRules;
import com.yamareviewer.domain.ids.Rules;
import com.yamareviewer.domain.model.Contract;
import static org.junit.Assert.assertEquals;
import org.junit.Test;

/** The pinned numbers of spec 6.9. */
public class DrainModelTest
{
	private static DrainModel normal()
	{
		return DrainModel.forFight(ContractRules.of(Contract.NONE, Rules.DEFAULT), Rules.DEFAULT);
	}

	private static DrainModel contract()
	{
		return DrainModel.forFight(ContractRules.of(Contract.CATALYST_ACQUISITION, Rules.DEFAULT), Rules.DEFAULT);
	}

	@Test
	public void startsAtTheFightsBase()
	{
		assertEquals(new YamaStats(225, 250, 0), normal().stats());
		assertEquals(new YamaStats(247, 275, 0), contract().stats());
	}

	@Test
	public void elderMaulTakesThirtyFivePercentOfCurrentDefence()
	{
		assertEquals(147, normal().apply(0, SpecWeapon.ELDER_MAUL.drainRule(), 60).getDefence());
	}

	@Test
	public void sceptreStopsAtTheNormalFormLimitsAndDoesNotStack()
	{
		DrainModel model = normal();

		YamaStats first = model.apply(0, SpecWeapon.ACCURSED_SCEPTRE.drainRule(), 30);
		YamaStats second = model.apply(5, SpecWeapon.ACCURSED_SCEPTRE.drainRule(), 30);

		assertEquals(191, first.getDefence());
		assertEquals(212, first.getMagic());
		assertEquals(first, second);
	}

	@Test
	public void sceptreDrainsTwiceUnderAContract()
	{
		DrainModel model = contract();

		YamaStats first = model.apply(0, SpecWeapon.ACCURSED_SCEPTRE.drainRule(), 30);
		YamaStats second = model.apply(5, SpecWeapon.ACCURSED_SCEPTRE.drainRule(), 30);

		assertEquals(213, first.getDefence());
		assertEquals(237, first.getMagic());
		assertEquals(191, second.getDefence());
		assertEquals(212, second.getMagic());
	}

	@Test
	public void emberlightStacksDownToTheFloor()
	{
		DrainModel model = normal();
		DrainRule emberlight = SpecWeapon.EMBERLIGHT.drainRule();

		assertEquals(191, model.apply(0, emberlight, 40).getDefence());
		assertEquals(157, model.apply(4, emberlight, 40).getDefence());
		assertEquals(145, model.apply(8, emberlight, 40).getDefence());
	}

	@Test
	public void sceptreAfterThreeEmberlightsChangesNothing()
	{
		DrainModel model = normal();
		DrainRule emberlight = SpecWeapon.EMBERLIGHT.drainRule();
		model.apply(0, emberlight, 40);
		model.apply(4, emberlight, 40);
		model.apply(8, emberlight, 40);

		assertEquals(145, model.apply(12, SpecWeapon.ACCURSED_SCEPTRE.drainRule(), 30).getDefence());
	}

	@Test
	public void dragonWarhammerOnlyDrainsWhenItDealtDamage()
	{
		assertEquals(225, normal().apply(0, SpecWeapon.DRAGON_WARHAMMER.drainRule(), 0).getDefence());
		assertEquals(158, normal().apply(0, SpecWeapon.DRAGON_WARHAMMER.drainRule(), 50).getDefence());
	}

	@Test
	public void bandosGodswordDrainsTheDamageDealt()
	{
		assertEquals(180, normal().apply(0, SpecWeapon.BANDOS_GODSWORD.drainRule(), 45).getDefence());
		assertEquals(145, normal().apply(0, SpecWeapon.BANDOS_GODSWORD.drainRule(), 90).getDefence());
	}

	@Test
	public void eyeOfAyakAddsItsDamageToTheBonusDrained()
	{
		DrainModel model = normal();
		model.apply(0, SpecWeapon.EYE_OF_AYAK.drainRule(), 30);

		YamaStats stats = model.apply(3, SpecWeapon.EYE_OF_AYAK.drainRule(), 12);

		assertEquals(42, stats.getMagicDefenceBonusDrained());
		assertEquals(225, stats.getDefence());
		assertEquals(250, stats.getMagic());
	}

	@Test
	public void noDrainWeaponsChangeNothing()
	{
		assertEquals(new YamaStats(225, 250, 0), normal().apply(0, SpecWeapon.SOULFLAME_HORN.drainRule(), 30));
		assertEquals(new YamaStats(225, 250, 0), normal().apply(0, SpecWeapon.PURGING_STAFF.drainRule(), 30));
		assertEquals(new YamaStats(225, 250, 0), normal().apply(0, SpecWeapon.SARADOMIN_GODSWORD.drainRule(), 30));
	}

	@Test
	public void oneRestoreStepAfterAHundredTicks()
	{
		DrainModel model = normal();
		model.apply(0, SpecWeapon.ELDER_MAUL.drainRule(), 60);

		assertEquals(147, model.advanceTo(99).getDefence());
		assertEquals(148, model.advanceTo(100).getDefence());
		assertEquals(148, model.advanceTo(150).getDefence());
		assertEquals(150, model.advanceTo(300).getDefence());
	}

	@Test
	public void restoreGoesTowardsTheFightsBaseAndStopsThere()
	{
		DrainModel model = contract();
		model.apply(0, SpecWeapon.ACCURSED_SCEPTRE.drainRule(), 30);
		model.apply(5, SpecWeapon.ACCURSED_SCEPTRE.drainRule(), 30);

		YamaStats after = model.advanceTo(100);

		assertEquals(192, after.getDefence());
		assertEquals(213, after.getMagic());
		assertEquals(275, model.advanceTo(100_000).getMagic());
		assertEquals(0, after.getMagicDefenceBonusDrained());
	}

	@Test
	public void applyRestoresFirst()
	{
		DrainModel model = normal();
		model.apply(0, SpecWeapon.EMBERLIGHT.drainRule(), 40);

		assertEquals(158, model.apply(100, SpecWeapon.EMBERLIGHT.drainRule(), 40).getDefence());
	}
}
