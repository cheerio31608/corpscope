package io.corpscope.external.dart;

public class DartException extends RuntimeException {
    public DartException(String message) { super(message); }

    /** Only transient failures are eligible for Batch retry. */
    public static class Transient extends DartException {
        public Transient(String message) { super(message); }
    }
}
