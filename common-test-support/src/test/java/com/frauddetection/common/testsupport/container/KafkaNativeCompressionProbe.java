package com.frauddetection.common.testsupport.container;

import net.jpountz.lz4.LZ4Factory;
import org.xerial.snappy.Snappy;

/** Executed inside the Alpine Kafka image to prove both native implementations load there. */
public final class KafkaNativeCompressionProbe {

    private KafkaNativeCompressionProbe() {
    }

    public static void main(String[] args) throws Exception {
        String lz4Compressor = LZ4Factory.nativeInstance().fastCompressor().getClass().getName();
        String snappyVersion = Snappy.getNativeLibraryVersion();
        System.out.printf("lz4-native=%s snappy-native=%s%n", lz4Compressor, snappyVersion);
    }
}
