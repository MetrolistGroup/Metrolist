package android.util;

import java.util.ArrayList;
import java.util.TreeMap;

/** JVM backing for Media3 command sets; android.jar only provides throwing method stubs. */
public final class SparseBooleanArray {
    private final TreeMap<Integer, Boolean> values = new TreeMap<>();

    public void append(int key, boolean value) {
        values.put(key, value);
    }

    public void delete(int key) {
        values.remove(key);
    }

    public boolean get(int key) {
        return values.getOrDefault(key, false);
    }

    public int size() {
        return values.size();
    }

    public int keyAt(int index) {
        return new ArrayList<>(values.keySet()).get(index);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SparseBooleanArray && values.equals(((SparseBooleanArray) other).values);
    }

    @Override
    public int hashCode() {
        return values.hashCode();
    }
}
