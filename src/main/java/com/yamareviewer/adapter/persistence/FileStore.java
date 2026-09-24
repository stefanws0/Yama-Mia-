package com.yamareviewer.adapter.persistence;

import java.io.IOException;
import java.util.List;

/** Files under the plugin directory, addressed by relative "/"-separated paths. */
public interface FileStore
{
	/** Creates parent directories and replaces any existing file. */
	void write(String path, byte[] data) throws IOException;

	byte[] read(String path) throws IOException;

	/** Names of the files directly inside {@code dir}, ascending; empty when the directory doesn't exist. */
	List<String> list(String dir) throws IOException;

	void delete(String path) throws IOException;

	void rename(String from, String to) throws IOException;
}
