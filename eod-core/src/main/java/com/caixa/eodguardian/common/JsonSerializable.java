package com.caixa.eodguardian.common;

import java.util.Map;

/** Objects that expose an explicit JSON view (keeps internal state out of API payloads). */
public interface JsonSerializable {
    Map<String, Object> toMap();
}
