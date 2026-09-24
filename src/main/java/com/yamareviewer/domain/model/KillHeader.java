package com.yamareviewer.domain.model;

import lombok.Value;

/** Only facts about the recording itself; every game observation is an event. */
@Value
public class KillHeader
{
	String killId;
	long startEpochMs;
	long endEpochMs;
	String pluginVersion;
	int schemaVersion;
	/** IdRegistry.fingerprint() of the IDs used while recording. */
	String idsFingerprint;
	boolean capture;
}
