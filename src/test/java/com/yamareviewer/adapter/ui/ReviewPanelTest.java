package com.yamareviewer.adapter.ui;

import com.yamareviewer.domain.history.HistoryIndex;
import com.yamareviewer.domain.history.HistoryProjector;
import com.yamareviewer.domain.model.Contract;
import com.yamareviewer.domain.model.Mode;
import com.yamareviewer.domain.review.HiddenReason;
import com.yamareviewer.domain.review.KillReview;
import com.yamareviewer.domain.review.Section;
import com.yamareviewer.domain.text.ReviewFormatter;
import com.yamareviewer.domain.text.ReviewView;
import com.yamareviewer.testing.Reviews;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.swing.SwingUtilities;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Test;

public class ReviewPanelTest
{
	private final List<String> copied = new ArrayList<>();
	private final List<String> opened = new ArrayList<>();
	private ReviewPanel panel;

	@Before
	public void setUp() throws Exception
	{
		SwingUtilities.invokeAndWait(() -> panel = new ReviewPanel(copied::add));
		panel.onKillSelected(opened::add);
	}

	private static void flush() throws Exception
	{
		SwingUtilities.invokeAndWait(() ->
		{
		});
	}

	private static ReviewView view()
	{
		return ReviewFormatter.format(Reviews.sample().toBuilder().flares(Section.hidden(HiddenReason.IDS_NOT_CAPTURED)).build());
	}

	private static boolean contains(List<String> texts, String needle)
	{
		return texts.stream().anyMatch(text -> text.contains(needle));
	}

	@Test
	public void showsTheLastKillWithItsStatusAndCopiesItsText() throws Exception
	{
		assertFalse(panel.lastKillTab().copyButton().isEnabled());

		panel.show(view(), "clipboard text", new HistoryIndex(Map.of()));
		flush();

		List<String> texts = panel.lastKillTab().texts();
		assertTrue(texts.toString(), contains(texts, "Yama (solo) 4:12"));
		assertTrue(texts.toString(), contains(texts, "Complete"));
		assertTrue(texts.toString(), contains(texts, "Hidden: IDs not captured"));
		assertTrue(texts.toString(), contains(texts, "Shark x2: 1,600"));
		SwingUtilities.invokeAndWait(() -> panel.lastKillTab().copyButton().doClick());
		assertEquals(List.of("clipboard text"), copied);
	}

	@Test
	public void modeTabsFilterByContractAndOpenASelectedKill() throws Exception
	{
		KillReview none = Reviews.builder("k1", 1_000L, Mode.SOLO, Contract.NONE).build();
		KillReview bloodied = Reviews.builder("k2", 2_000L, Mode.SOLO, Contract.BLOODIED_BLOWS).build();
		KillReview host = Reviews.builder("k3", 3_000L, Mode.DUO_HOST, Contract.NONE).build();

		panel.showHistory(HistoryProjector.index(List.of(none, bloodied, host), 50));
		flush();

		HistoryTab solo = panel.historyTab(Mode.SOLO);
		assertEquals(List.of("k1"), solo.killIds());
		assertTrue(contains(solo.texts(), "1 kill"));
		assertEquals(List.of("k3"), panel.historyTab(Mode.DUO_HOST).killIds());
		assertTrue(contains(panel.historyTab(Mode.DUO_JOINER).texts(), "No kills yet"));

		SwingUtilities.invokeAndWait(() -> solo.contractFilter().setSelectedItem(Contract.BLOODIED_BLOWS.displayName()));
		flush();
		assertEquals(List.of("k2"), solo.killIds());

		SwingUtilities.invokeAndWait(() -> solo.killButtons().get(0).doClick());
		assertEquals(List.of("k2"), opened);
	}

	@Test
	public void aPastKillRendersInsideItsModeTab() throws Exception
	{
		panel.showKill(Mode.DUO_HOST, view(), "past text");
		flush();

		HistoryTab tab = panel.historyTab(Mode.DUO_HOST);
		assertTrue(contains(tab.texts(), "Yama (solo) 4:12"));
		SwingUtilities.invokeAndWait(() -> tab.selectedKill().copyButton().doClick());
		assertEquals(List.of("past text"), copied);
	}

	@Test
	public void theFooterIsEmptyUntilPart4()
	{
		assertEquals(0, panel.footer().getComponentCount());
	}
}
