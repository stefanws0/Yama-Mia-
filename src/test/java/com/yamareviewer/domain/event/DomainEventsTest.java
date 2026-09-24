package com.yamareviewer.domain.event;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class DomainEventsTest
{
	@Test
	public void typeNamesAreUnique()
	{
		long distinct = Arrays.stream(EventType.values()).map(EventType::typeName).distinct().count();
		assertEquals(EventType.values().length, distinct);
	}

	@Test
	public void everyEventClassHasAType()
	{
		Set<String> eventClasses = new ClassFileImporter().importPackages("com.yamareviewer.domain.event").stream()
			.filter(c -> c.isAssignableTo(DomainEvent.class) && !c.isInterface())
			.map(JavaClass::getName)
			.collect(Collectors.toSet());
		Set<String> typed = Arrays.stream(EventType.values()).map(t -> t.eventClass().getName()).collect(Collectors.toSet());
		assertEquals(eventClasses, typed);
	}

	@Test
	public void typesResolveBothWays()
	{
		assertEquals(EventType.HITSPLAT, EventType.of(new HitsplatObserved(1, Actor.SELF, HitsplatKind.DAMAGE, 5, 1, false)));
		assertEquals(Optional.of(EventType.TICK), EventType.byName("tick"));
		assertEquals(Optional.empty(), EventType.byName("from-the-future"));
	}

	@Test
	public void missingCollectionsReadAsEmpty()
	{
		assertTrue(new TickState(1, null, 99, 99, 100, 100, -1, null, null, null).getPrayers().isEmpty());
		assertTrue(new SuppliesSnapshot(1, SnapshotKind.START, null).getItems().isEmpty());
	}
}
