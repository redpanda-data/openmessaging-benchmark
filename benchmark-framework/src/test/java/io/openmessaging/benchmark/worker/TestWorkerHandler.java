package io.openmessaging.benchmark.worker;

import org.junit.Test;
import org.junit.Assert.*;

import io.openmessaging.benchmark.worker.WorkerHandler;

import static org.junit.Assert.assertEquals;

import java.nio.ByteBuffer;
import java.util.Random;

import org.HdrHistogram.Histogram;


public class TestWorkerHandler {


    /**
     * Create a random histogram and insert the given number of samples.
     */
    private Histogram randomHisto(int samples) {
        Random r = new Random(0xBADBEEF);
        Histogram h = new org.HdrHistogram.Histogram(5);
        for (int i = 0; i < samples; i++) {
            h.recordValue(r.nextInt(10000000));
        }

        return h;
    }

    byte[] serializeRandomHisto(int samples, int initialBufferSize) throws Exception {
        ByteBuffer inbuffer = ByteBuffer.allocate(initialBufferSize);
        Histogram inHisto = randomHisto(samples);
        byte[] serialBytes = WorkerHandler.toByteArray(WorkerHandler.serializeHistogram(inHisto, inbuffer));

        // check roundtrip
        Histogram outHisto = Histogram.decodeFromCompressedByteBuffer(ByteBuffer.wrap(serialBytes), 0);
        assertEquals(inHisto, outHisto);

        return serialBytes;
    }

    @Test
    public void testHistogram() throws Exception {

        // in the worker it's 1 MB but it takes a while to make a histogram that big
        final int BUF_SIZE = 1002;

        int samples = 300;

        // we do an exponential search to fit the crossover point where we need to grow the buffer
        while (true) {
            byte[] serialBytes = serializeRandomHisto(samples, BUF_SIZE);
            // System.out.println("Samples: " + samples + ", histogram size: " + serialBytes.length);
            if (serialBytes.length >= BUF_SIZE) {
                break;
            }
            samples *= 1.05;
        }

        // then walk backwards across the point linearly with increment of 1 to check the boundary
        // carefully
        while (true) {
            samples--;
            byte[] serialBytes = serializeRandomHisto(samples, BUF_SIZE);
            // System.out.println("Samples: " + samples + ", histogram size: " + serialBytes.length);
            if (serialBytes.length < BUF_SIZE - 10) {
                break;
            }
        }
    }

}
