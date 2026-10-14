package dev.hardrush.core.api;

/** Elapsed-time source. Its values are meaningful only as differences within one process. */
@FunctionalInterface
public interface MonotonicClock {
    long nanoTime();

    static MonotonicClock system() { return System::nanoTime; }
}
