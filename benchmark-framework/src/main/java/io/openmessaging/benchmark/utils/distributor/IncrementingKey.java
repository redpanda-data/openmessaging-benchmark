package io.openmessaging.benchmark.utils.distributor;

import java.nio.ByteBuffer;

public class IncrementingKey extends KeyDistributor {

  private int value = 0;

  private final ByteBuffer buffer = ByteBuffer.allocate(Integer.SIZE / Byte.SIZE);

  @Override
  public String next() {
    return String.valueOf(value++);
  }

  @Override
  public byte[] nextBytes() {
    buffer.putInt(0, value++);
    return buffer.array().clone();
  }
}
