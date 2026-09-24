package com.yamareviewer.domain.projection;

import com.yamareviewer.domain.event.EndReason;
import com.yamareviewer.domain.model.KillLog;
import com.yamareviewer.domain.review.HiddenReason;
import com.yamareviewer.domain.review.KillReview;
import com.yamareviewer.domain.review.Section;
import com.yamareviewer.domain.review.TickLog;
import com.yamareviewer.testing.KillLogBuilder;
import com.yamareviewer.testing.TestContext;
import java.util.List;
import java.util.Optional;
import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class KillReviewAssemblerP3Test
{
	private final KillLog log = KillLogBuilder.kill().ticks(5).end(EndReason.YAMA_DIED);

	@Test
	public void copiesThePart3SectionsFromTheContext()
	{
		ProjectionContext context = TestContext.withPhases(5);
		Section<TickLog> tickLog = Section.ok(new TickLog(List.of(), 7));
		context.put(Sections.TICK_LOG, tickLog);
		context.put(Sections.SPECS, Section.hidden(HiddenReason.IDS_NOT_CAPTURED));

		KillReview review = KillReviewAssembler.assemble(log, context);

		assertEquals(tickLog, review.getTickLog());
		assertEquals(Optional.of(HiddenReason.IDS_NOT_CAPTURED), review.getSpecs().hiddenReason());
		assertEquals(Optional.of(HiddenReason.NOT_APPLICABLE), review.getPrayerReview().hiddenReason());
		assertEquals(Optional.of(HiddenReason.NOT_APPLICABLE), review.getOpener().hiddenReason());
		assertEquals(Optional.of(HiddenReason.NOT_APPLICABLE), review.getCrashes().hiddenReason());
		assertEquals(Optional.of(HiddenReason.NOT_APPLICABLE), review.getWaves().hiddenReason());
	}
}
