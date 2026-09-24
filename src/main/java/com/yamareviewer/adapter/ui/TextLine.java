package com.yamareviewer.adapter.ui;

import java.awt.Color;
import java.awt.Font;
import javax.swing.JLabel;
import net.runelite.client.ui.ColorScheme;

/** Wrapping labels for the fixed-width panel; the plain text is kept as a client property for tests. */
final class TextLine
{
	static final String PLAIN_TEXT = "yama-reviewer.plain-text";
	private static final int WRAP_WIDTH_PX = 195;

	private TextLine()
	{
	}

	static JLabel label(String text)
	{
		JLabel label = new JLabel("<html><body style='width:" + WRAP_WIDTH_PX + "px'>" + escape(text) + "</body></html>");
		label.putClientProperty(PLAIN_TEXT, text);
		label.setForeground(ColorScheme.TEXT_COLOR);
		return label;
	}

	static JLabel title(String text)
	{
		JLabel label = label(text);
		label.setFont(label.getFont().deriveFont(Font.BOLD));
		label.setForeground(Color.WHITE);
		return label;
	}

	static JLabel muted(String text)
	{
		JLabel label = label(text);
		label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		return label;
	}

	static String escape(String text)
	{
		return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}
}
