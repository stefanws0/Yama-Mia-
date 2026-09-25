package com.yamareviewer.adapter.ui;

import com.yamareviewer.domain.history.HistoryIndex;
import com.yamareviewer.domain.model.Mode;
import com.yamareviewer.domain.text.ReviewView;
import java.awt.BorderLayout;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;

/**
 * The side panel of spec 7.3: tabs Last kill, Solo, Duo host, Duo joiner; a contract filter per mode tab;
 * a Copy button on every review; a footer reserved for Part 4's "Report a problem". It renders only
 * ReviewView and HistoryView, and every public update hops to the Swing thread itself.
 */
public class ReviewPanel extends PluginPanel
{
	private final JTabbedPane tabs = new JTabbedPane();
	private final ReviewTab lastKill;
	private final Map<Mode, HistoryTab> modeTabs = new EnumMap<>(Mode.class);
	private final JPanel footer = new JPanel(new BorderLayout());
	private Consumer<String> killOpener = killId ->
	{
	};
	private ReportProblemFooter reportFooter;

	public ReviewPanel(Consumer<String> clipboard)
	{
		super(false);
		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		lastKill = new ReviewTab(clipboard, "No kill reviewed yet");
		tabs.addTab("Last kill", scroll(lastKill));
		for (Mode mode : Mode.values())
		{
			HistoryTab tab = new HistoryTab(mode, clipboard, killId -> killOpener.accept(killId));
			modeTabs.put(mode, tab);
			tabs.addTab(tabTitle(mode), scroll(tab));
		}
		footer.setBackground(ColorScheme.DARK_GRAY_COLOR);
		add(tabs, BorderLayout.CENTER);
		add(footer, BorderLayout.SOUTH);
	}

	/** Called with a kill id when the user selects a kill in a history tab. */
	public void onKillSelected(Consumer<String> opener)
	{
		killOpener = opener;
	}

	/** Part 4 puts "Report a problem" here. */
	public JPanel footer()
	{
		return footer;
	}

	/** Part 4: the "Report a problem" footer. Called once by the plugin, on the Swing thread, before the panel is shown. */
	public void attachFooter(ReportProblemFooter reportFooter)
	{
		this.reportFooter = reportFooter;
		footer.removeAll();
		footer.add(reportFooter, BorderLayout.CENTER);
		footer.revalidate();
		reportFooter.refresh();
	}

	@Override
	public void onActivate()
	{
		if (reportFooter != null)
		{
			reportFooter.refresh();
		}
	}

	public void show(ReviewView view, String clipboardText, HistoryIndex history)
	{
		SwingUtilities.invokeLater(() ->
		{
			lastKill.render(view, clipboardText);
			renderHistory(history);
			tabs.setSelectedIndex(0);
		});
		if (reportFooter != null)
		{
			reportFooter.refresh();
		}
	}

	public void showHistory(HistoryIndex history)
	{
		SwingUtilities.invokeLater(() -> renderHistory(history));
	}

	/** A past kill opened from a history tab, rendered inside that mode's tab. */
	public void showKill(Mode mode, ReviewView view, String clipboardText)
	{
		SwingUtilities.invokeLater(() -> modeTabs.get(mode).renderKill(view, clipboardText));
	}

	ReviewTab lastKillTab()
	{
		return lastKill;
	}

	HistoryTab historyTab(Mode mode)
	{
		return modeTabs.get(mode);
	}

	private void renderHistory(HistoryIndex history)
	{
		modeTabs.values().forEach(tab -> tab.render(history));
	}

	private static JScrollPane scroll(JComponent content)
	{
		JScrollPane pane = new JScrollPane(content);
		pane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		pane.setBorder(null);
		pane.getVerticalScrollBar().setUnitIncrement(16);
		return pane;
	}

	private static String tabTitle(Mode mode)
	{
		String label = mode.label();
		return label.substring(0, 1).toUpperCase(Locale.ROOT) + label.substring(1);
	}
}
