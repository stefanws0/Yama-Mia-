package com.yamareviewer.domain.model;

/** How the kill was done; the first half of a history key (spec 5.4). */
public enum Mode
{
	SOLO("solo"),
	DUO_HOST("duo host"),
	DUO_JOINER("duo joiner");

	private final String label;

	Mode(String label)
	{
		this.label = label;
	}

	public String label()
	{
		return label;
	}
}
