package com.yamareviewer.adapter.publish;

import com.yamareviewer.domain.text.ChatLineOptions;
import java.util.function.Supplier;
import javax.inject.Inject;
import net.runelite.client.chat.ChatMessageManager;

/**
 * Injected into the plugin so that only this package depends on ChatMessageManager
 * (ArchitectureTest.onlyPublishTalksToChat); the plugin never sees the chat manager itself.
 */
public final class ChatReviewPublisherFactory
{
	private final ChatMessageManager chatMessageManager;

	@Inject
	public ChatReviewPublisherFactory(ChatMessageManager chatMessageManager)
	{
		this.chatMessageManager = chatMessageManager;
	}

	public ChatReviewPublisher create(Supplier<ChatLineOptions> options)
	{
		return new ChatReviewPublisher(chatMessageManager, options);
	}
}
