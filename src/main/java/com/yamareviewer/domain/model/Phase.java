package com.yamareviewer.domain.model;

/** The five phases of a kill, in order (spec 3). */
public enum Phase
{
	P1("P1"),
	JUDGE_1("Judge 1"),
	P2("P2"),
	JUDGE_2("Judge 2"),
	P3("P3");

	private final String label;

	Phase(String label)
	{
		this.label = label;
	}

	public String label()
	{
		return label;
	}

	public boolean isJudge()
	{
		return this == JUDGE_1 || this == JUDGE_2;
	}
}
