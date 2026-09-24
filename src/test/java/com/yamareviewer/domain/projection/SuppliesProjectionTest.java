package com.yamareviewer.domain.projection;

import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.event.FightEnded;
import com.yamareviewer.domain.event.SnapshotKind;
import com.yamareviewer.domain.event.SupplyItem;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.ids.Rules;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.review.HiddenReason;
import com.yamareviewer.domain.review.PriceMode;
import com.yamareviewer.domain.review.Section;
import com.yamareviewer.domain.review.SupplyLine;
import com.yamareviewer.domain.review.SupplySummary;
import com.yamareviewer.testing.KillLogBuilder;
import com.yamareviewer.testing.TestIds;
import java.util.List;
import java.util.Set;
import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class SuppliesProjectionTest
{
	private static final int WIDGET = TestIds.id(Role.CONTRACT_NAME_WIDGET);
	private static final SupplyItem SHARK_5 = new SupplyItem(385, "Shark", 5, 800, 90);
	private static final SupplyItem SHARK_3 = new SupplyItem(385, "Shark", 3, 900, 90);

	private static Section<SupplySummary> section(KillLog kill, ReviewSettings settings)
	{
		ReviewBuilder builder = new ReviewBuilder(List.of(new ContractProjection(), new SuppliesProjection()), TestIds.registry(), Rules.DEFAULT);
		return builder.run(kill, settings).section(Sections.SUPPLIES);
	}

	private static SupplySummary project(KillLog kill)
	{
		return section(kill, ReviewSettings.DEFAULT).value();
	}

	private static SupplyItem potion(String name, int quantity, int gePrice)
	{
		return new SupplyItem(name.hashCode(), name, quantity, gePrice, gePrice / 10);
	}

	@Test
	public void onlyDecreasesCountPricedFromTheStartSnapshot()
	{
		KillLog kill = KillLogBuilder.kill()
			.supplies(SnapshotKind.START, SHARK_5, new SupplyItem(13441, "Anglerfish", 2, 2000, 100), new SupplyItem(11212, "Dragon arrow", 100, 1500, 300))
			.ticks(5)
			.supplies(SnapshotKind.END, SHARK_3, new SupplyItem(13441, "Anglerfish", 4, 2000, 100), new SupplyItem(11212, "Dragon arrow", 90, 1500, 300))
			.end(EndReason.YAMA_DIED);

		SupplySummary summary = project(kill);

		assertEquals(List.of(
			new SupplyLine("Shark", 2, "x", 1_600L, false),
			new SupplyLine("Dragon arrow", 10, "x", 15_000L, false)), summary.getLines());
		assertEquals(16_600L, summary.getTotalCost());
		assertEquals(PriceMode.GRAND_EXCHANGE, summary.getPriceMode());
	}

	@Test
	public void potionsAreCountedInDosesPricedByTheHighestDoseVariant()
	{
		KillLog kill = KillLogBuilder.kill()
			.supplies(SnapshotKind.START, potion("Super restore(4)", 3, 12_000), potion("Super restore(2)", 1, 5_000))
			.ticks(5)
			.supplies(SnapshotKind.END, potion("Super restore(4)", 1, 12_000), potion("Super restore(2)", 1, 5_000), potion("Super restore(1)", 1, 2_000))
			.end(EndReason.YAMA_DIED);

		assertEquals(List.of(new SupplyLine("Super restore", 7, "doses", 21_000L, false)), project(kill).getLines());
	}

	@Test
	public void endOnlyPotionVariantsCountAsRemainingDoses()
	{
		KillLog kill = KillLogBuilder.kill()
			.supplies(SnapshotKind.START, potion("Super restore(4)", 3, 12_000))
			.ticks(5)
			.supplies(SnapshotKind.END, potion("Super restore(4)", 1, 12_000), potion("Super restore(2)", 1, 5_000))
			.end(EndReason.YAMA_DIED);

		assertEquals(List.of(new SupplyLine("Super restore", 6, "doses", 18_000L, false)), project(kill).getLines());
	}

	@Test
	public void anItemMissingFromTheEndSnapshotIsFullyUsed()
	{
		KillLog kill = KillLogBuilder.kill().supplies(SnapshotKind.START, SHARK_5).ticks(5).supplies(SnapshotKind.END).end(EndReason.YAMA_DIED);

		assertEquals(List.of(new SupplyLine("Shark", 5, "x", 4_000L, false)), project(kill).getLines());
	}

	@Test
	public void highAlchemyPricesWhenConfigured()
	{
		KillLog kill = KillLogBuilder.kill().supplies(SnapshotKind.START, SHARK_5).ticks(5).supplies(SnapshotKind.END, SHARK_3).end(EndReason.YAMA_DIED);

		SupplySummary summary = section(kill, new ReviewSettings(PriceMode.HIGH_ALCHEMY, null)).value();

		assertEquals(List.of(new SupplyLine("Shark", 2, "x", 180L, false)), summary.getLines());
		assertEquals(PriceMode.HIGH_ALCHEMY, summary.getPriceMode());
	}

	@Test
	public void aLeftKillWithoutAnEndSnapshotHidesSupplies()
	{
		KillLog kill = KillLogBuilder.kill().supplies(SnapshotKind.START, SHARK_5).ticks(3).end(EndReason.LEFT);

		assertEquals(Section.hidden(HiddenReason.NOT_APPLICABLE), section(kill, ReviewSettings.DEFAULT));
	}

	@Test
	public void aLeftKillWithAnEndSnapshotIsPriced()
	{
		KillLog kill = KillLogBuilder.kill().supplies(SnapshotKind.START, SHARK_5).ticks(3).supplies(SnapshotKind.END, SHARK_3).end(EndReason.LEFT);

		assertEquals(List.of(new SupplyLine("Shark", 2, "x", 1_600L, false)), project(kill).getLines());
	}

	@Test
	public void aFinishedKillWithoutAnEndSnapshotIsAnError()
	{
		KillLog kill = KillLogBuilder.kill().supplies(SnapshotKind.START, SHARK_5).ticks(3).event(new FightEnded(3, EndReason.YAMA_DIED)).build();

		assertEquals(Section.hidden(HiddenReason.ERROR), section(kill, ReviewSettings.DEFAULT));
	}

	@Test(expected = IllegalStateException.class)
	public void aMissingSnapshotThrowsSoTheBuilderLogsIt()
	{
		KillLog kill = KillLogBuilder.kill().ticks(3).event(new FightEnded(3, EndReason.PLAYER_DIED)).build();

		new SuppliesProjection().project(kill, new ProjectionContext(TestIds.registry(), Rules.DEFAULT, ReviewSettings.DEFAULT));
	}

	@Test
	public void underFamiliarAcquisitionFoodAndPotionsHaveNoEffectButRunesAndAmmunitionDo()
	{
		KillLog kill = KillLogBuilder.kill()
			.widgetText(WIDGET, "Contract of Familiar Acquisition")
			.supplies(SnapshotKind.START, SHARK_5, potion("Prayer potion(4)", 2, 10_000), new SupplyItem(565, "Blood rune", 500, 300, 60),
				new SupplyItem(11212, "Dragon arrow", 100, 1500, 300))
			.ticks(5)
			.supplies(SnapshotKind.END, SHARK_3, potion("Prayer potion(4)", 1, 10_000), new SupplyItem(565, "Blood rune", 400, 300, 60),
				new SupplyItem(11212, "Dragon arrow", 90, 1500, 300))
			.end(EndReason.YAMA_DIED);

		SupplySummary summary = project(kill);

		assertEquals(List.of(
			new SupplyLine("Shark", 2, "x", 1_600L, true),
			new SupplyLine("Prayer potion", 4, "doses", 10_000L, true),
			new SupplyLine("Blood rune", 100, "x", 30_000L, false),
			new SupplyLine("Dragon arrow", 10, "x", 15_000L, false)), summary.getLines());
		assertEquals(56_600L, summary.getTotalCost());
	}

	@Test
	public void keyAndRoles()
	{
		SuppliesProjection projection = new SuppliesProjection();

		assertEquals(Sections.SUPPLIES, projection.key());
		assertEquals(Set.of(), projection.requiredRoles());
	}
}
