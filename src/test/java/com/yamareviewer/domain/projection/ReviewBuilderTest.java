package com.yamareviewer.domain.projection;

import com.yamareviewer.domain.contract.ContractRules;
import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.ids.IdRegistry;
import com.yamareviewer.domain.ids.Role;
import com.yamareviewer.domain.ids.Rules;
import com.yamareviewer.domain.model.Contract;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.model.Mode;
import com.yamareviewer.domain.review.HiddenReason;
import com.yamareviewer.domain.review.PriceMode;
import com.yamareviewer.domain.review.Section;
import com.yamareviewer.testing.KillLogBuilder;
import com.yamareviewer.testing.TestIds;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class ReviewBuilderTest
{
	private static final SectionKey<String> FIRST = SectionKey.of("first");
	private static final SectionKey<String> SECOND = SectionKey.of("second");

	private final KillLog kill = KillLogBuilder.kill().ticks(2).end(EndReason.YAMA_DIED);

	private static Projection<String> projection(SectionKey<String> key, Set<Role> roles,
		BiFunction<KillLog, ProjectionContext, Section<String>> body)
	{
		return new Projection<String>()
		{
			@Override
			public SectionKey<String> key()
			{
				return key;
			}

			@Override
			public Set<Role> requiredRoles()
			{
				return roles;
			}

			@Override
			public Section<String> project(KillLog log, ProjectionContext context)
			{
				return body.apply(log, context);
			}
		};
	}

	@SafeVarargs
	private static ReviewBuilder builder(IdRegistry ids, Projection<String>... projections)
	{
		return new ReviewBuilder(List.of(projections), ids, Rules.DEFAULT);
	}

	@Test
	public void runsProjectionsInOrderAndLaterOnesSeeEarlierSections()
	{
		ProjectionContext context = builder(TestIds.registry(),
			projection(FIRST, Set.of(), (log, ctx) -> Section.ok("a")),
			projection(SECOND, Set.of(), (log, ctx) -> Section.ok(ctx.value(FIRST).orElse("?") + "b")))
			.run(kill, ReviewSettings.DEFAULT);

		assertEquals(Section.ok("a"), context.section(FIRST));
		assertEquals(Section.ok("ab"), context.section(SECOND));
	}

	@Test
	public void anUncapturedRequiredRoleHidesTheSectionWithoutRunningIt()
	{
		ProjectionContext context = builder(TestIds.registryWithout(Role.JUDGE),
			projection(FIRST, EnumSet.of(Role.YAMA, Role.JUDGE), (log, ctx) ->
			{
				throw new AssertionError("must not run");
			}))
			.run(kill, ReviewSettings.DEFAULT);

		assertEquals(Section.hidden(HiddenReason.IDS_NOT_CAPTURED), context.section(FIRST));
	}

	@Test
	public void anExceptionHidesTheSectionWithErrorAndTheRestStillRuns()
	{
		ProjectionContext context = builder(TestIds.registry(),
			projection(FIRST, Set.of(), (log, ctx) ->
			{
				throw new IllegalStateException("boom");
			}),
			projection(SECOND, Set.of(), (log, ctx) -> Section.ok("still " + ctx.section(FIRST).hiddenReason().get())))
			.run(kill, ReviewSettings.DEFAULT);

		assertEquals(Section.hidden(HiddenReason.ERROR), context.section(FIRST));
		assertEquals(Section.ok("still ERROR"), context.section(SECOND));
	}

	@Test
	public void aNullSectionIsAnError()
	{
		ProjectionContext context = builder(TestIds.registry(), projection(FIRST, Set.of(), (log, ctx) -> null))
			.run(kill, ReviewSettings.DEFAULT);

		assertEquals(Section.hidden(HiddenReason.ERROR), context.section(FIRST));
	}

	@Test
	public void aSectionNobodyProducedIsNotApplicable()
	{
		ProjectionContext context = builder(TestIds.registry()).run(kill, ReviewSettings.DEFAULT);

		assertEquals(Section.hidden(HiddenReason.NOT_APPLICABLE), context.section(SECOND));
		assertEquals(Optional.empty(), context.value(SECOND));
	}

	@Test
	public void contractRulesFollowTheContractSection()
	{
		ProjectionContext context = new ProjectionContext(TestIds.registry(), Rules.DEFAULT, ReviewSettings.DEFAULT);
		assertEquals(2500, context.contractRules().getYamaHp());

		context.put(Sections.CONTRACT, Section.ok(Contract.BLOODIED_BLOWS));

		assertEquals(ContractRules.of(Contract.BLOODIED_BLOWS, Rules.DEFAULT), context.contractRules());
	}

	@Test
	public void settingsIdsAndRulesAreAvailableToProjections()
	{
		ReviewSettings settings = new ReviewSettings(PriceMode.HIGH_ALCHEMY, Mode.DUO_HOST);
		Rules rules = Rules.DEFAULT.toBuilder().specResultWindow(9).build();
		Projection<String> reads = projection(FIRST, Set.of(), (log, ctx) -> Section.ok(
			ctx.settings().getPriceMode() + "/" + ctx.settings().getModeOverride() + "/" + ctx.rules().getSpecResultWindow()
				+ "/" + ctx.ids().isCaptured(Role.YAMA)));

		ProjectionContext context = new ReviewBuilder(List.of(reads), TestIds.registry(), rules).run(kill, settings);

		assertEquals(Section.ok("HIGH_ALCHEMY/DUO_HOST/9/true"), context.section(FIRST));
		assertEquals(new ReviewSettings(PriceMode.GRAND_EXCHANGE, null), ReviewSettings.DEFAULT);
	}
}
