package com.yamareviewer.adapter.persistence;

import java.io.FileNotFoundException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

public final class InMemoryFileStore implements FileStore
{
	private final Map<String, byte[]> files = new TreeMap<>();

	@Override
	public void write(String path, byte[] data)
	{
		files.put(path, data.clone());
	}

	@Override
	public byte[] read(String path) throws FileNotFoundException
	{
		byte[] data = files.get(path);
		if (data == null)
		{
			throw new FileNotFoundException(path);
		}
		return data.clone();
	}

	@Override
	public List<String> list(String dir)
	{
		String prefix = dir + "/";
		return files.keySet().stream()
			.filter(path -> path.startsWith(prefix) && path.indexOf('/', prefix.length()) < 0)
			.map(path -> path.substring(prefix.length()))
			.sorted()
			.collect(Collectors.toList());
	}

	@Override
	public void delete(String path)
	{
		files.remove(path);
	}

	@Override
	public void rename(String from, String to) throws FileNotFoundException
	{
		byte[] data = files.remove(from);
		if (data == null)
		{
			throw new FileNotFoundException(from);
		}
		files.put(to, data);
	}

	public Set<String> paths()
	{
		return files.keySet();
	}
}
