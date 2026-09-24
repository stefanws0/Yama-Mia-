package com.yamareviewer.domain.projection;

import java.util.List;
import static java.util.stream.Collectors.toList;
import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class ProjectionsTest
{
	@Test
	public void theStandardOrderOfSpec61()
	{
		List<SectionKey<?>> keys = Projections.standard().stream().map(Projection::key).collect(toList());

		assertEquals(List.of(Sections.PHASES, Sections.CONTRACT, Sections.MODE, Sections.FLARES, Sections.DAMAGE, Sections.SUPPLIES,
			Sections.DEATH_RECAP), keys);
	}
}
