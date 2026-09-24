package com.yamareviewer.adapter.persistence;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.runelite.client.util.Filepath;

/** The production FileStore, rooted at Plugin.getPluginDirectory(). */
public final class FilepathFileStore implements FileStore
{
	private final Filepath root;

	public FilepathFileStore(Filepath root)
	{
		this.root = root;
	}

	@Override
	public void write(String path, byte[] data) throws IOException
	{
		Filepath target = resolve(path);
		target.getParent().createDirectories();
		Filepath temp = resolve(path + ".tmp");
		temp.write(data);
		temp.moveTo(target, StandardCopyOption.REPLACE_EXISTING);
	}

	@Override
	public byte[] read(String path) throws IOException
	{
		try (InputStream in = resolve(path).openInputStream())
		{
			return in.readAllBytes();
		}
	}

	@Override
	public List<String> list(String dir) throws IOException
	{
		Filepath directory = resolve(dir);
		if (!directory.isDirectory())
		{
			return List.of();
		}
		try (Stream<Filepath> entries = directory.walk(1))
		{
			return entries
				.filter(entry -> !entry.equals(directory) && entry.isFile())
				.map(Filepath::getFileName)
				.sorted()
				.collect(Collectors.toList());
		}
	}

	@Override
	public void delete(String path) throws IOException
	{
		resolve(path).deleteIfExists();
	}

	@Override
	public void rename(String from, String to) throws IOException
	{
		resolve(from).moveTo(resolve(to), StandardCopyOption.REPLACE_EXISTING);
	}

	private Filepath resolve(String path)
	{
		String[] parts = path.split("/");
		return root.join(parts[0], Arrays.copyOfRange(parts, 1, parts.length));
	}
}
