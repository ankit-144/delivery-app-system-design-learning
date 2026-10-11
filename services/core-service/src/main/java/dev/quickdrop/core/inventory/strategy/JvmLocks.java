package dev.quickdrop.core.inventory.strategy;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.stereotype.Component;

/**
 * One lock object per store, living in this JVM's memory. Coarse on purpose: every order for the
 * same store waits behind the same lock. And it only protects requests that land on <em>this</em>
 * instance — scenario 3.1 runs two instances and the oversell comes back.
 */
@Component
public class JvmLocks {

    private final ConcurrentMap<Long, Object> locks = new ConcurrentHashMap<>();

    public Object forStore(long storeId) {
        return locks.computeIfAbsent(storeId, id -> new Object());
    }
}
