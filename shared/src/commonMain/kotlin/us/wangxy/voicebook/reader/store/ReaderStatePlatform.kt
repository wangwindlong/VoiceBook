package us.wangxy.voicebook.reader.store

/**
 * Platform storage location for the reader's persisted state (server config, history,
 * font size). Each platform writes a single JSON file; a failed write is swallowed like
 * a failed preferences write would be.
 */
expect fun createReaderStateStore(): ReaderStateStore
