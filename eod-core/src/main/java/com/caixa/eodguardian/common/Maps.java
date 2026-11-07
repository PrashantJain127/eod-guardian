package com.caixa.eodguardian.common;

import java.util.LinkedHashMap;
import java.util.Map;

/** Ordered map builder that tolerates null values (unlike Map.of). */
public final class Maps {
    private Maps() {}

    public static Map<String, Object> of(Object... kv) {
        if (kv.length % 2 != 0) throw new IllegalArgumentException("Odd number of key/value arguments");
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }
}
