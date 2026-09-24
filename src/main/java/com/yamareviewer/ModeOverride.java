package com.yamareviewer;

import com.yamareviewer.domain.model.Mode;

/** The modeOverride config value (spec 10). */
public enum ModeOverride
{
	AUTO(null),
	SOLO(Mode.SOLO),
	DUO_HOST(Mode.DUO_HOST),
	DUO_JOINER(Mode.DUO_JOINER);

	private final Mode mode;

	ModeOverride(Mode mode)
	{
		this.mode = mode;
	}

	/** Null for AUTO: detect the mode. */
	public Mode mode()
	{
		return mode;
	}
}
