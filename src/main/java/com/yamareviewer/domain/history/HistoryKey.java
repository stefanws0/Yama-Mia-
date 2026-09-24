package com.yamareviewer.domain.history;

import com.yamareviewer.domain.model.Contract;
import com.yamareviewer.domain.model.Mode;
import lombok.Value;

/** Kills are only compared like for like: per mode and per contract (spec 6.3). */
@Value
public class HistoryKey
{
	Mode mode;
	Contract contract;
}
