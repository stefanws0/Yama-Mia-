package com.yamareviewer.domain.history;

import java.util.Map;
import java.util.Optional;
import lombok.Value;

@Value
public class HistoryIndex
{
	Map<HistoryKey, HistoryView> views;

	public Optional<HistoryView> view(HistoryKey key)
	{
		return Optional.ofNullable(views.get(key));
	}
}
