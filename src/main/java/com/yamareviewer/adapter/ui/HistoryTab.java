package com.yamareviewer.adapter.ui;

import com.yamareviewer.domain.history.HistoryIndex;
import com.yamareviewer.domain.history.HistoryKey;
import com.yamareviewer.domain.history.HistoryView;
import com.yamareviewer.domain.history.KillSummary;
import com.yamareviewer.domain.model.Contract;
import com.yamareviewer.domain.model.Mode;
import com.yamareviewer.domain.text.HistoryFormatter;
import com.yamareviewer.domain.text.ReviewView;
import java.awt.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import net.runelite.client.ui.ColorScheme;

/** One mode: a contract filter, the statistics of that mode and contract, the kill list, and the selected kill's review. */
final class HistoryTab extends JPanel
{
	private static final List<Contract> FILTER = filterOrder();

	private final Mode mode;
	private final Consumer<String> opener;
	private final JComboBox<String> filter = new JComboBox<>(FILTER.stream().map(Contract::displayName).toArray(String[]::new));
	private final JPanel summary = column();
	private final JPanel kills = column();
	private final List<JButton> killButtons = new ArrayList<>();
	private final ReviewTab selected;
	private HistoryIndex index = new HistoryIndex(Map.of());

	HistoryTab(Mode mode, Consumer<String> clipboard, Consumer<String> opener)
	{
		this.mode = mode;
		this.opener = opener;
		this.selected = new ReviewTab(clipboard, "Select a kill above to see its review");
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		filter.addActionListener(event -> renderKills());
		add(TextLine.muted("Contract"));
		add(filter);
		add(summary);
		add(Box.createVerticalStrut(8));
		add(kills);
		add(Box.createVerticalStrut(8));
		add(selected);
		renderKills();
	}

	void render(HistoryIndex history)
	{
		index = history;
		renderKills();
	}

	void renderKill(ReviewView view, String clipboardText)
	{
		selected.render(view, clipboardText);
	}

	JComboBox<String> contractFilter()
	{
		return filter;
	}

	List<JButton> killButtons()
	{
		return List.copyOf(killButtons);
	}

	List<String> killIds()
	{
		return killButtons.stream().map(button -> button.getClientProperty(KILL_ID).toString()).collect(Collectors.toList());
	}

	ReviewTab selectedKill()
	{
		return selected;
	}

	List<String> texts()
	{
		List<String> texts = new ArrayList<>();
		for (Component component : summary.getComponents())
		{
			if (component instanceof JLabel)
			{
				texts.add(((JLabel) component).getClientProperty(TextLine.PLAIN_TEXT).toString());
			}
		}
		texts.addAll(selected.texts());
		return texts;
	}

	private static final String KILL_ID = "yama-reviewer.kill-id";

	private void renderKills()
	{
		Contract contract = FILTER.get(Math.max(0, filter.getSelectedIndex()));
		Optional<HistoryView> view = index.view(new HistoryKey(mode, contract));
		summary.removeAll();
		kills.removeAll();
		killButtons.clear();
		List<String> lines = view.map(HistoryFormatter::summaryLines).orElse(List.of("No kills yet"));
		for (String line : lines)
		{
			summary.add(TextLine.label(line));
		}
		if (view.isPresent())
		{
			for (KillSummary kill : view.get().getKills())
			{
				JButton button = new JButton(HistoryFormatter.killLine(kill));
				button.setHorizontalAlignment(SwingConstants.LEFT);
				button.putClientProperty(KILL_ID, kill.getKillId());
				button.addActionListener(event -> opener.accept(kill.getKillId()));
				killButtons.add(button);
				kills.add(button);
			}
		}
		revalidate();
		repaint();
	}

	private static JPanel column()
	{
		JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		panel.setAlignmentX(JComponent.LEFT_ALIGNMENT);
		return panel;
	}

	/** No contract first, then the contracts of spec 6.3.1 in order, then the unknown contract. */
	private static List<Contract> filterOrder()
	{
		List<Contract> order = new ArrayList<>();
		order.add(Contract.NONE);
		for (Contract contract : Contract.values())
		{
			if (contract.shortName() != null)
			{
				order.add(contract);
			}
		}
		order.add(Contract.UNKNOWN_CONTRACT);
		return List.copyOf(order);
	}
}
