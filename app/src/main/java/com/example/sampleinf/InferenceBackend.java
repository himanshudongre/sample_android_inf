package com.example.sampleinf;

import android.graphics.Bitmap;

import java.util.List;

/**
 * A common interface implemented by every inference engine in this app.
 *
 * <p>Decoupling the UI from the engine behind this interface is what lets the
 * same screen drive two very different runtimes — the low-level
 * {@link LiteRtClassifier} (raw LiteRT {@code Interpreter}) and the high-level
 * {@link MediaPipeClassifier} (MediaPipe Tasks) — without the UI knowing or
 * caring which one it is talking to. When you later add an EUPE ViT backend,
 * implement this interface and the UI needs no changes.
 *
 * <p>Extends {@link AutoCloseable} so native resources (the interpreter, the
 * MediaPipe graph) are released deterministically in {@link #close()}.
 */
public interface InferenceBackend extends AutoCloseable {

    /** Short display name shown in the UI, e.g. "LiteRT" or "MediaPipe". */
    String name();

    /**
     * Runs the model on {@code bitmap} and returns the top results, highest
     * score first.
     *
     * @param bitmap an ARGB_8888 bitmap of any size; the backend handles resizing
     *               and preprocessing internally.
     */
    List<Classification> classify(Bitmap bitmap);

    /** Releases native resources. Safe to call more than once. */
    @Override
    void close();
}
