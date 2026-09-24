package com.yamareviewer.adapter.persistence;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.yamareviewer.domain.event.DomainEvent;
import com.yamareviewer.domain.event.EventType;
import com.yamareviewer.domain.model.KillHeader;
import java.util.Optional;

/** One JSON object per line: {"type": "<stable type name>", "data": {...}}. */
public final class EventCodec
{
	private final Gson gson;

	public EventCodec(Gson gson)
	{
		this.gson = gson;
	}

	public String encodeHeader(KillHeader header)
	{
		return gson.toJson(header);
	}

	public KillHeader decodeHeader(String line)
	{
		return gson.fromJson(line, KillHeader.class);
	}

	public String encode(DomainEvent event)
	{
		JsonObject wrapper = new JsonObject();
		wrapper.addProperty("type", EventType.of(event).typeName());
		wrapper.add("data", gson.toJsonTree(event));
		return gson.toJson(wrapper);
	}

	/** Empty for a type this version doesn't know. */
	public Optional<DomainEvent> decode(String line)
	{
		JsonObject wrapper = gson.fromJson(line, JsonObject.class);
		return EventType.byName(wrapper.get("type").getAsString())
			.<DomainEvent>map(type -> gson.fromJson(wrapper.get("data"), type.eventClass()));
	}
}
