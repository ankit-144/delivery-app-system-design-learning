package dev.quickdrop.core.redis;

import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;

/** Recognises "Redis is down or not answering" among the exceptions Spring Data Redis throws. */
public final class RedisUnavailable {

    private RedisUnavailable() {
    }

    public static boolean is(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof RedisConnectionFailureException || t instanceof QueryTimeoutException
                    || t instanceof DataAccessResourceFailureException || t instanceof RedisSystemException) {
                return true;
            }
        }
        return false;
    }
}
