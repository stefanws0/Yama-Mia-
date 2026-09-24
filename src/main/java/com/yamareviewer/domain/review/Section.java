package com.yamareviewer.domain.review;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * A review section: a value, or the reason it is hidden. Gson-serialisable through its two fields
 * ({@code value}, {@code hidden}); a section read back with neither is treated as hidden with ERROR.
 */
public final class Section<T>
{
	private final T value;
	private final HiddenReason hidden;

	private Section(T value, HiddenReason hidden)
	{
		this.value = value;
		this.hidden = hidden;
	}

	public static <T> Section<T> ok(T value)
	{
		return new Section<>(Objects.requireNonNull(value, "value"), null);
	}

	public static <T> Section<T> hidden(HiddenReason reason)
	{
		return new Section<>(null, Objects.requireNonNull(reason, "reason"));
	}

	public boolean isOk()
	{
		return hidden == null && value != null;
	}

	/** @throws IllegalStateException when hidden */
	public T value()
	{
		if (!isOk())
		{
			throw new IllegalStateException("Section is hidden: " + hiddenReason().map(HiddenReason::name).orElse("?"));
		}
		return value;
	}

	public Optional<T> asOptional()
	{
		return isOk() ? Optional.of(value) : Optional.empty();
	}

	public Optional<HiddenReason> hiddenReason()
	{
		if (isOk())
		{
			return Optional.empty();
		}
		return Optional.of(hidden == null ? HiddenReason.ERROR : hidden);
	}

	public <R> Section<R> map(Function<? super T, ? extends R> mapper)
	{
		return isOk() ? Section.<R>ok(mapper.apply(value)) : Section.<R>hidden(hiddenReason().get());
	}

	@Override
	public boolean equals(Object other)
	{
		if (this == other)
		{
			return true;
		}
		if (!(other instanceof Section))
		{
			return false;
		}
		Section<?> that = (Section<?>) other;
		return Objects.equals(value, that.value) && hidden == that.hidden;
	}

	@Override
	public int hashCode()
	{
		return Objects.hash(value, hidden);
	}

	@Override
	public String toString()
	{
		return isOk() ? "Section.ok(" + value + ")" : "Section.hidden(" + hiddenReason().get() + ")";
	}
}
