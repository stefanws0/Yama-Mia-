package com.yamareviewer.adapter.ui;

import java.awt.HeadlessException;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import lombok.extern.slf4j.Slf4j;

/** The Copy button's target. */
@Slf4j
public final class SystemClipboard
{
	private SystemClipboard()
	{
	}

	public static void copy(String text)
	{
		try
		{
			Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
		}
		catch (HeadlessException | IllegalStateException e)
		{
			log.warn("Could not copy the review to the clipboard", e);
		}
	}
}
