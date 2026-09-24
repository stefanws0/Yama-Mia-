package com.yamareviewer.adapter.publish;

import com.yamareviewer.application.port.ReviewPublisher;
import com.yamareviewer.domain.history.HistoryIndex;
import com.yamareviewer.domain.review.KillReview;
import com.yamareviewer.domain.text.ChatLineOptions;
import com.yamareviewer.domain.text.ChatLines;
import java.util.function.Supplier;
import net.runelite.api.ChatMessageType;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;

/** The chat summary of spec 7.2. ChatMessageManager.queue is thread-safe, so this runs on the plugin executor. */
public final class ChatReviewPublisher implements ReviewPublisher
{
	private final ChatMessageManager chatMessageManager;
	private final Supplier<ChatLineOptions> options;

	public ChatReviewPublisher(ChatMessageManager chatMessageManager, Supplier<ChatLineOptions> options)
	{
		this.chatMessageManager = chatMessageManager;
		this.options = options;
	}

	@Override
	public void publish(KillReview review, HistoryIndex history)
	{
		for (String line : ChatLines.lines(review, options.get()))
		{
			chatMessageManager.queue(QueuedMessage.builder()
				.type(ChatMessageType.GAMEMESSAGE)
				.runeLiteFormattedMessage(line)
				.build());
		}
	}

	@Override
	public void showHistory(HistoryIndex history)
	{
		// The history is only shown in the panel.
	}
}
