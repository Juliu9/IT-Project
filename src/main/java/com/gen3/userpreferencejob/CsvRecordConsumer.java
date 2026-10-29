package com.gen3.userpreferencejob;

import java.io.IOException;

/** Receives one validated CSV record without retaining the whole input. */
@FunctionalInterface
public interface CsvRecordConsumer<T> {
  void accept(T record) throws IOException;
}
