package com.yamareviewer.adapter.persistence;

import java.io.IOException;
import java.util.List;
import net.runelite.client.util.Filepath;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class FilepathFileStoreTest
{
	@Rule
	public TemporaryFolder folder = new TemporaryFolder();

	private FilepathFileStore store;

	@Before
	public void setUp()
	{
		// Filepath.Unchecked is fine in tests: the Plugin Hub never compiles them.
		store = new FilepathFileStore(Filepath.Unchecked.getRooted(folder.getRoot().toPath()));
	}

	@Test
	public void writesReadsListsRenamesAndDeletes() throws IOException
	{
		store.write("raw/b.jsonl.gz", new byte[]{2});
		store.write("raw/a.jsonl.gz", new byte[]{1});

		assertEquals(List.of("a.jsonl.gz", "b.jsonl.gz"), store.list("raw"));
		assertArrayEquals(new byte[]{1}, store.read("raw/a.jsonl.gz"));

		store.rename("raw/a.jsonl.gz", "raw/a.jsonl.gz.corrupt");
		store.delete("raw/b.jsonl.gz");

		assertEquals(List.of("a.jsonl.gz.corrupt"), store.list("raw"));
	}

	@Test
	public void listingAMissingDirectoryIsEmpty() throws IOException
	{
		assertEquals(List.of(), store.list("reviews"));
	}

	@Test(expected = IllegalArgumentException.class)
	public void pathsCannotEscapeTheRoot() throws IOException
	{
		store.write("../outside.txt", new byte[]{1});
	}
}
