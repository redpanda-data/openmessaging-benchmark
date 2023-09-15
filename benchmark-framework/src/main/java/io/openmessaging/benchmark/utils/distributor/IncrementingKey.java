package io.openmessaging.benchmark.utils.distributor;

import java.nio.ByteBuffer;

public class IncrementingKey extends KeyDistributor {

  int value = 0;
  final int len = getLength(); // This never changes in the parent class?

  final ByteBuffer buffer = ByteBuffer.allocate(8);

  @Override
  public String next() {
    return String.valueOf((value++) % len);
  }

  @Override
  public byte[] nextBytes() {
    buffer.putInt(0, (value++) % len);
    return buffer.array();
  }
}
