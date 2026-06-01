package com.example.sampleinf;

import java.util.Locale;

/**
 * One classification result: a human-readable {@code label} and the model's
 * confidence {@code score} (typically in the range 0..1 after softmax).
 *
 * <p>This is a tiny, immutable value object shared by both inference backends
 * ({@link LiteRtClassifier} and {@link MediaPipeClassifier}) so the UI can treat
 * their outputs identically.
 */
public final class Classification {

    public final String label;
    public final float score;

    public Classification(String label, float score) {
        this.label = label;
        this.score = score;
    }

    /** e.g. "Egyptian cat            0.823" */
    @Override
    public String toString() {
        return String.format(Locale.US, "%-24s %.3f", label, score);
    }
}
