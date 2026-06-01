package com.example.sampleinf;

import android.content.Context;
import android.graphics.Bitmap;

import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.components.containers.Category;
import com.google.mediapipe.tasks.components.containers.Classifications;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.imageclassifier.ImageClassifier;
import com.google.mediapipe.tasks.vision.imageclassifier.ImageClassifierResult;

import java.util.ArrayList;
import java.util.List;

/**
 * HIGH-LEVEL inference using the MediaPipe Tasks {@code ImageClassifier}.
 *
 * <p>Contrast this with {@link LiteRtClassifier}: there is no resize code, no
 * normalization code, no softmax, and no labels file. MediaPipe reads all of
 * that from the <b>model metadata</b> embedded inside the .tflite file and does
 * the right thing automatically. That convenience is also the limitation — it
 * only works for models packaged with the metadata MediaPipe expects, which is
 * why the EUPE ViT port will lean on the LiteRT path instead.
 *
 * <p>Under the hood MediaPipe Tasks still runs the model on LiteRT — so this
 * single sample genuinely exercises BOTH third-party dependencies.
 */
public final class MediaPipeClassifier implements InferenceBackend {

    /** Same model file the LiteRT path uses; it must contain TFLite metadata. */
    private static final String MODEL_ASSET = "efficientnet_lite0.tflite";

    /** How many top results to return. */
    private static final int TOP_K = 3;

    private final ImageClassifier classifier;
    private boolean closed = false;

    public MediaPipeClassifier(Context context) {
        // BaseOptions tells MediaPipe where the model is. setModelAssetPath reads
        // straight from the APK's assets/ directory by file name.
        BaseOptions baseOptions = BaseOptions.builder()
                .setModelAssetPath(MODEL_ASSET)
                .build();

        ImageClassifier.ImageClassifierOptions options =
                ImageClassifier.ImageClassifierOptions.builder()
                        .setBaseOptions(baseOptions)
                        // IMAGE = classify a single still image (vs VIDEO / LIVE_STREAM).
                        .setRunningMode(RunningMode.IMAGE)
                        .setMaxResults(TOP_K)
                        .build();

        this.classifier = ImageClassifier.createFromOptions(context, options);
    }

    @Override
    public String name() {
        return "MediaPipe";
    }

    @Override
    public List<Classification> classify(Bitmap bitmap) {
        // Wrap the Android Bitmap in MediaPipe's image type.
        MPImage image = new BitmapImageBuilder(bitmap).build();

        // Run inference. MediaPipe handles resize + normalization internally.
        ImageClassifierResult result = classifier.classify(image);

        List<Classification> results = new ArrayList<>();
        // A model can have multiple "classification heads"; image classifiers
        // have exactly one, so we read head 0.
        List<Classifications> classifications = result.classificationResult().classifications();
        if (!classifications.isEmpty()) {
            for (Category category : classifications.get(0).categories()) {
                // Prefer the friendly display name when the metadata provides one.
                String label = category.displayName();
                if (label == null || label.isEmpty()) {
                    label = category.categoryName();
                }
                results.add(new Classification(label, category.score()));
            }
        }
        return results;
    }

    @Override
    public void close() {
        if (!closed) {
            classifier.close();
            closed = true;
        }
    }
}
