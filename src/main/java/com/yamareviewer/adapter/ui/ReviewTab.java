package com.yamareviewer.adapter.ui;

import com.yamareviewer.domain.text.ReviewView;
import com.yamareviewer.domain.text.ViewSection;
import java.awt.Color;
import java.awt.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import net.runelite.client.ui.ColorScheme;

/** One review: headline, status badge, Copy button, then the sections. Renders a ReviewView and nothing else. */
final class ReviewTab extends JPanel
{
	private final Consumer<String> clipboard;
	private final JButton copy = new JButton("Copy");
	private String clipboardText;

	ReviewTab(Consumer<String> clipboard, String emptyText)
	{
		this.clipboard = clipboard;
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		copy.setEnabled(false);
		copy.addActionListener(event ->
		{
			if (clipboardText != null)
			{
				clipboard.accept(clipboardText);
			}
		});
		removeAll();
		add(TextLine.muted(emptyText));
	}

	void render(ReviewView view, String text)
	{
		clipboardText = text;
		copy.setEnabled(true);
		removeAll();
		add(TextLine.title(view.getHeadline()));
		JLabel status = TextLine.label(view.getStatus());
		status.setOpaque(true);
		status.setForeground(Color.BLACK);
		status.setBackground("Complete".equals(view.getStatus()) ? ColorScheme.PROGRESS_COMPLETE_COLOR : ColorScheme.PROGRESS_INPROGRESS_COLOR);
		add(status);
		add(copy);
		for (ViewSection section : view.getSections())
		{
			add(Box.createVerticalStrut(8));
			add(TextLine.title(section.getTitle()));
			if (section.getHiddenReason() != null)
			{
				add(TextLine.muted("Hidden: " + section.getHiddenReason()));
			}
			for (String line : section.getLines())
			{
				add(TextLine.label(line));
			}
		}
		revalidate();
		repaint();
	}

	JButton copyButton()
	{
		return copy;
	}

	List<String> texts()
	{
		List<String> texts = new ArrayList<>();
		for (Component component : getComponents())
		{
			if (component instanceof JLabel)
			{
				Object plain = ((JLabel) component).getClientProperty(TextLine.PLAIN_TEXT);
				if (plain != null)
				{
					texts.add(plain.toString());
				}
			}
		}
		return texts;
	}
}
