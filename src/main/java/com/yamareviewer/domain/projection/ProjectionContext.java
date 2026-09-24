package com.yamareviewer.domain.projection;

import com.yamareviewer.domain.contract.ContractRules;
import com.yamareviewer.domain.ids.IdRegistry;
import com.yamareviewer.domain.ids.Rules;
import com.yamareviewer.domain.model.Contract;
import com.yamareviewer.domain.review.HiddenReason;
import com.yamareviewer.domain.review.Section;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** The sections produced so far plus everything a projection may read besides the log. Not thread-safe. */
public final class ProjectionContext
{
	private final IdRegistry ids;
	private final Rules rules;
	private final ReviewSettings settings;
	private final Map<SectionKey<?>, Section<?>> sections = new LinkedHashMap<>();

	public ProjectionContext(IdRegistry ids, Rules rules, ReviewSettings settings)
	{
		this.ids = ids;
		this.rules = rules;
		this.settings = settings;
	}

	public IdRegistry ids()
	{
		return ids;
	}

	public Rules rules()
	{
		return rules;
	}

	public ReviewSettings settings()
	{
		return settings;
	}

	/** ContractRules.of(value(Sections.CONTRACT).orElse(Contract.NONE), rules()). */
	public ContractRules contractRules()
	{
		return ContractRules.of(value(Sections.CONTRACT).orElse(Contract.NONE), rules);
	}

	/** Hidden(NOT_APPLICABLE) when no projection produced this key. */
	@SuppressWarnings("unchecked")
	public <T> Section<T> section(SectionKey<T> key)
	{
		Section<?> section = sections.get(key);
		return section == null ? Section.hidden(HiddenReason.NOT_APPLICABLE) : (Section<T>) section;
	}

	public <T> Optional<T> value(SectionKey<T> key)
	{
		return section(key).asOptional();
	}

	public <T> void put(SectionKey<T> key, Section<T> section)
	{
		sections.put(key, section);
	}
}
