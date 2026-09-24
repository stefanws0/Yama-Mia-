package com.yamareviewer.domain.projection;

/** Identity-compared key of a section in the ProjectionContext. */
public final class SectionKey<T>
{
	private final String name;

	private SectionKey(String name)
	{
		this.name = name;
	}

	public static <T> SectionKey<T> of(String name)
	{
		return new SectionKey<>(name);
	}

	public String name()
	{
		return name;
	}

	@Override
	public String toString()
	{
		return name;
	}
}
