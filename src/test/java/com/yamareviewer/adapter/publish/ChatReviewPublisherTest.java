package com.yamareviewer.adapter.publish;

import com.yamareviewer.domain.history.HistoryIndex;
import com.yamareviewer.domain.history.HistoryProjector;
import com.yamareviewer.domain.review.KillReview;
import com.yamareviewer.domain.text.ChatLineOptions;
import com.yamareviewer.domain.text.ChatLines;
import com.yamareviewer.testing.Reviews;
import java.util.List;
import static java.util.stream.Collectors.toList;
import net.runelite.api.ChatMessageType;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

public class ChatReviewPublisherTest
{
	private final ChatMessageManager chat = mock(ChatMessageManager.class);
	private final HistoryIndex history = HistoryProjector.index(List.of(), 50);
	private ChatLineOptions options = new ChatLineOptions(true, true, true, true, false);
	private final ChatReviewPublisher publisher = new ChatReviewPublisher(chat, () -> options);

	@Test
	public void queuesOneGameMessagePerEnabledLine()
	{
		KillReview review = Reviews.sample();

		publisher.publish(review, history);

		ArgumentCaptor<QueuedMessage> captor = ArgumentCaptor.forClass(QueuedMessage.class);
		verify(chat, times(4)).queue(captor.capture());
		assertTrue(captor.getAllValues().stream().allMatch(message -> message.getType() == ChatMessageType.GAMEMESSAGE));
		assertEquals(ChatLines.lines(review, options), captor.getAllValues().stream().map(QueuedMessage::getRuneLiteFormattedMessage).collect(toList()));
	}

	@Test
	public void readsTheOptionsAtPublishTime()
	{
		options = new ChatLineOptions(true, false, false, false, false);

		publisher.publish(Reviews.sample(), history);

		verify(chat, times(1)).queue(org.mockito.ArgumentMatchers.any(QueuedMessage.class));
	}

	@Test
	public void historyIsNotChatted()
	{
		publisher.showHistory(history);

		verifyNoInteractions(chat);
	}

	@Test
	public void theFactoryBuildsAPublisher()
	{
		ChatReviewPublisher built = new ChatReviewPublisherFactory(chat).create(() -> options);

		built.publish(Reviews.sample(), history);

		verify(chat, times(4)).queue(org.mockito.ArgumentMatchers.any(QueuedMessage.class));
	}
}
