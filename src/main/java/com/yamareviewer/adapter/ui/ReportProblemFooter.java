package com.yamareviewer.adapter.ui;

import com.yamareviewer.application.port.ReportRepository;
import com.yamareviewer.domain.text.IssueLink;
import java.awt.BorderLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.util.LinkBrowser;

/**
 * The panel footer of spec 7.3 and 9: enabled when a report exists; on click it copies the latest report to the
 * clipboard and opens a prefilled GitHub issue with the summary. Files are read on the plugin executor, never
 * on the Swing thread; nothing is sent anywhere by the plugin itself.
 */
@Slf4j
public class ReportProblemFooter extends JPanel
{
	static final String NO_REPORT = "No report yet";
	static final String REPORT_READY = "Latest report ready to send";
	static final String REPORT_COPIED = "Report copied to the clipboard; the issue opens in your browser";

	private final ExecutorService executor;
	private final ReportRepository reports;
	private final String repositoryUrl;
	private final JButton button = new JButton("Report a problem");
	private final JLabel hint = new JLabel(NO_REPORT);

	public ReportProblemFooter(ExecutorService executor, ReportRepository reports, String repositoryUrl)
	{
		this.executor = executor;
		this.reports = reports;
		this.repositoryUrl = repositoryUrl;
		setLayout(new BorderLayout(0, 4));
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		setBorder(new EmptyBorder(8, 0, 0, 0));
		button.setEnabled(false);
		button.setFocusPainted(false);
		button.setToolTipText("Copies the latest report to the clipboard and opens a GitHub issue with its summary");
		button.addActionListener(event -> report());
		hint.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		hint.setFont(hint.getFont().deriveFont(11f));
		add(button, BorderLayout.NORTH);
		add(hint, BorderLayout.SOUTH);
	}

	/** Finds out on the executor whether a report exists and enables the button on the Swing thread. Safe from any thread. */
	public void refresh()
	{
		submit(() ->
		{
			boolean present = reports.latest().isPresent();
			SwingUtilities.invokeLater(() -> setAvailable(present, present ? REPORT_READY : NO_REPORT));
		});
	}

	boolean isReportAvailable()
	{
		return button.isEnabled();
	}

	String hintText()
	{
		return hint.getText();
	}

	private void report()
	{
		button.setEnabled(false);
		submit(() ->
		{
			Optional<String> report = reports.latest();
			SwingUtilities.invokeLater(() ->
			{
				if (report.isEmpty())
				{
					setAvailable(false, NO_REPORT);
					return;
				}
				copyToClipboard(report.get());
				LinkBrowser.browse(IssueLink.url(repositoryUrl, report.get()));
				setAvailable(true, REPORT_COPIED);
			});
		});
	}

	private void submit(FileTask task)
	{
		try
		{
			executor.submit(() ->
			{
				try
				{
					task.run();
				}
				catch (IOException | RuntimeException e)
				{
					log.warn("Could not read the latest report", e);
					SwingUtilities.invokeLater(() -> setAvailable(false, NO_REPORT));
				}
			});
		}
		catch (RejectedExecutionException e)
		{
			log.debug("Plugin stopped; report footer not refreshed");
		}
	}

	private void setAvailable(boolean available, String text)
	{
		button.setEnabled(available);
		hint.setText(text);
	}

	private static void copyToClipboard(String text)
	{
		Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
	}

	@FunctionalInterface
	private interface FileTask
	{
		void run() throws IOException;
	}
}
