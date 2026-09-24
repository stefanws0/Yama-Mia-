package com.yamareviewer.application.command;

import com.yamareviewer.domain.model.KillLog;

public interface KillEndedListener
{
	/** Called on the client thread; implementations must hand real work to another thread. */
	void killEnded(KillLog kill);
}
