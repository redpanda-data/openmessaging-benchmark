package io.openmessaging.benchmark.utils.distributor;

public class IncrementingKey extends KeyDistributor {

  int value = 0;
  final int len = getLength();

  @Override
  public String next() {
    return String.valueOf((value++) % len);
  }
}
