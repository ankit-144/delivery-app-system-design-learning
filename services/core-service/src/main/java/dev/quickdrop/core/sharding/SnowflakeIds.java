package dev.quickdrop.core.sharding;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 64-bit ids that are unique across shards and instances without asking anyone — scenario 5.4.
 *
 * <pre>
 *  0 | 41 bits: milliseconds since 2025-01-01 | 5 bits: shard | 5 bits: instance | 12 bits: sequence
 * </pre>
 * Roughly time-ordered (good for B-tree indexes), 4,096 ids per millisecond per instance, and the shard
 * is readable from the id itself, so "find order 123" goes straight to the right shard.
 */
@Component
public class SnowflakeIds {

    private static final long EPOCH = 1735689600000L;   // 2025-01-01T00:00:00Z

    private final long instance;
    private long lastMillis = -1;
    private long sequence;

    public SnowflakeIds(@Value("${quickdrop.instance-name}") String instanceName) {
        String digits = instanceName.replaceAll("\\D", "");
        this.instance = digits.isEmpty() ? 0 : Long.parseLong(digits) & 31;
    }

    public synchronized long next(int shard) {
        long now = System.currentTimeMillis();
        if (now < lastMillis) {
            now = lastMillis;   // the clock went backwards (NTP): keep ids increasing rather than risk duplicates
        }
        if (now == lastMillis) {
            sequence = (sequence + 1) & 4095;
            if (sequence == 0) {
                while (System.currentTimeMillis() <= lastMillis) {
                    Thread.onSpinWait();   // 4,096 ids used up this millisecond: wait for the next one
                }
                now = System.currentTimeMillis();
            }
        } else {
            sequence = 0;
        }
        lastMillis = now;
        return ((now - EPOCH) << 22) | ((long) (shard & 31) << 17) | (instance << 12) | sequence;
    }

    public static int shardOf(long id) {
        return (int) ((id >> 17) & 31);
    }
}
