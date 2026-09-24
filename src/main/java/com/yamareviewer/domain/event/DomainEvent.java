package com.yamareviewer.domain.event;

/**
 * A raw observation made during a fight. Events never contain a classification
 * such as "magic attack" or "wrong prayer"; that happens after the kill.
 * Collections passed into events must be unmodifiable copies.
 */
public interface DomainEvent
{
	/** Game ticks since the fight started. */
	int getTick();
}
