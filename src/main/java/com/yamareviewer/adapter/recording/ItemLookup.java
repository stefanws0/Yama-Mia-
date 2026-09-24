package com.yamareviewer.adapter.recording;

/** Item facts; implementations may only be called on the client thread. */
public interface ItemLookup
{
	String name(int itemId);

	int gePrice(int itemId);

	int haPrice(int itemId);
}
