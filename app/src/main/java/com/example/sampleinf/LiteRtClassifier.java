package com.example.sampleinf;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;

import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.Tensor;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * LOW-LEVEL inference using the LiteRT {@link Interpreter} API directly.
 *
 * <p>This is the backend that matters most for the EUPE ViT port. Unlike the
 * high-level MediaPipe Tasks API, here we are fully responsible for the whole
 * pipeline:
 *
 * <pre>
 *   bytes in assets  ──load──►  Interpreter
 *   Bitmap  ──resize+normalize (ImageUtils)──►  input tensor (ByteBuffer)
 *   Interpreter.run(input, output)
 *   output tensor  ──(softmax)+top-k+label lookup──►  List&lt;Classification&gt;
 * </pre>
 *
 * <p>It is written generically — it reads the model's input/output tensor shapes
 * and data types at runtime — so retargeting it to a different .tflite model
 * (e.g. EUPE ViT) is mostly a matter of editing the CONFIG block below and
 * dropping in the new model + labels. See docs/06_porting_eupe_vit.md.
 */
public final class LiteRtClassifier implements InferenceBackend {

    // =========================================================================
    // CONFIG  —  edit this block when porting to a new model (e.g. EUPE ViT).
    // =========================================================================

    /** Model file inside app/src/main/assets (populated by tools/download_model.sh). */
    private static final String MODEL_ASSET = "efficientnet_lite0.tflite";

    /** Label list inside assets, one class name per line, in the model's class order. */
    private static final String LABELS_ASSET = "labels.txt";

    /**
     * Normalization recipe applied to each 0..255 colour channel as
     * (value - MEAN) / STD. Must match how the model was trained.
     *   EfficientNet-Lite (this sample): MEAN=127.5, STD=127.5  → range [-1, 1]
     *   Many PyTorch ViTs:               value/255 then per-channel ImageNet
     *                                    mean/std (would need a small code change)
     */
    private static final float NORM_MEAN = 127.5f;
    private static final float NORM_STD = 127.5f;

    /** Input tensor memory layout. TensorFlow exports are usually NHWC; PyTorch
     *  (litert-torch) exports are often NCHW. */
    private static final ImageUtils.Layout LAYOUT = ImageUtils.Layout.NHWC;

    /** Apply softmax to the model output. Leave true if the model emits raw
     *  logits; set false if it already outputs probabilities. (Either way the
     *  ranking/top-1 is unchanged; this only affects the printed scores.) */
    private static final boolean APPLY_SOFTMAX = true;

    /** Number of CPU threads the interpreter may use. */
    private static final int NUM_THREADS = 4;

    /** How many top results to return. */
    private static final int TOP_K = 3;

    // =========================================================================
    // End CONFIG
    // =========================================================================

    private final Interpreter interpreter;
    private final List<String> labels;
    private boolean closed = false;

    /**
     * Loads the model and labels from app assets and constructs the interpreter.
     *
     * @throws IOException if the model/labels are missing — typically because
     *         tools/download_model.sh has not been run yet.
     */
    public LiteRtClassifier(Context context) throws IOException {
        MappedByteBuffer model = loadModelFile(context, MODEL_ASSET);

        Interpreter.Options options = new Interpreter.Options();
        options.setNumThreads(NUM_THREADS);
        // (To use the GPU delegate later, add the LiteRT GPU artifact and a
        //  GpuDelegate here. CPU is plenty for this sample.)
        this.interpreter = new Interpreter(model, options);

        this.labels = loadLabels(context, LABELS_ASSET);
    }

    @Override
    public String name() {
        return "LiteRT";
    }

    @Override
    public List<Classification> classify(Bitmap bitmap) {
        // --- 1. Inspect the model's input tensor so we adapt to its shape/type.
        Tensor inputTensor = interpreter.getInputTensor(0);
        int[] inShape = inputTensor.shape(); // e.g. [1,224,224,3] or [1,3,224,224]
        DataType inType = inputTensor.dataType();

        final int height, width;
        if (LAYOUT == ImageUtils.Layout.NHWC) {
            height = inShape[1];
            width = inShape[2];
        } else { // NCHW
            height = inShape[2];
            width = inShape[3];
        }

        // --- 2. Resize + build the input buffer in the format the model wants.
        Bitmap resized = ImageUtils.resize(bitmap, width, height);
        ByteBuffer input;
        if (inType == DataType.UINT8) {
            input = ImageUtils.toUint8Buffer(resized, width, height);
        } else { // FLOAT32 (the common case, including this sample's model)
            input = ImageUtils.toFloat32Buffer(resized, width, height, NORM_MEAN, NORM_STD, LAYOUT);
        }

        // --- 3. Inspect the output tensor and allocate a matching container.
        Tensor outputTensor = interpreter.getOutputTensor(0);
        int[] outShape = outputTensor.shape();          // typically [1, numClasses]
        int numClasses = outShape[outShape.length - 1];
        DataType outType = outputTensor.dataType();

        float[] probs;
        if (outType == DataType.UINT8) {
            // Quantized output: dequantize with the tensor's scale/zero-point.
            byte[][] raw = new byte[1][numClasses];
            interpreter.run(input, raw);
            Tensor.QuantizationParams q = outputTensor.quantizationParams();
            probs = new float[numClasses];
            for (int i = 0; i < numClasses; i++) {
                int v = raw[0][i] & 0xFF; // unsigned
                probs[i] = (v - q.getZeroPoint()) * q.getScale();
            }
        } else { // FLOAT32
            float[][] raw = new float[1][numClasses];
            interpreter.run(input, raw);
            probs = raw[0];
        }

        // --- 4. Optionally softmax, then take the top-K with labels.
        if (APPLY_SOFTMAX) {
            probs = ImageUtils.softmax(probs);
        }
        return topK(probs, TOP_K);
    }

    /** Picks the highest-scoring indices and maps them to labels. */
    private List<Classification> topK(float[] probs, int k) {
        List<Integer> indices = new ArrayList<>(probs.length);
        for (int i = 0; i < probs.length; i++) indices.add(i);

        // Sort indices by descending probability.
        Collections.sort(indices, new Comparator<Integer>() {
            @Override
            public int compare(Integer a, Integer b) {
                return Float.compare(probs[b], probs[a]);
            }
        });

        List<Classification> results = new ArrayList<>();
        int limit = Math.min(k, indices.size());
        for (int i = 0; i < limit; i++) {
            int idx = indices.get(i);
            // Our download script extracts labels straight from the model's
            // metadata, so counts line up. Guard anyway for safety.
            String label = (idx < labels.size()) ? labels.get(idx) : ("class_" + idx);
            results.add(new Classification(label, probs[idx]));
        }
        return results;
    }

    @Override
    public void close() {
        if (!closed) {
            interpreter.close();
            closed = true;
        }
    }

    // --- helpers -------------------------------------------------------------

    /**
     * Memory-maps the .tflite file straight out of the APK's assets. Memory
     * mapping (vs reading into a byte[]) lets the OS page model weights in lazily
     * and share them read-only — the standard, efficient way to load a model.
     */
    private static MappedByteBuffer loadModelFile(Context context, String asset) throws IOException {
        AssetFileDescriptor fd = context.getAssets().openFd(asset);
        try (FileInputStream is = new FileInputStream(fd.getFileDescriptor())) {
            FileChannel channel = is.getChannel();
            return channel.map(
                    FileChannel.MapMode.READ_ONLY, fd.getStartOffset(), fd.getDeclaredLength());
        } finally {
            fd.close();
        }
    }

    /** Reads labels.txt (one label per line) from assets. */
    private static List<String> loadLabels(Context context, String asset) throws IOException {
        List<String> result = new ArrayList<>();
        try (InputStream is = context.getAssets().open(asset);
             BufferedReader reader =
                     new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                result.add(cleanLabel(line));
            }
        }
        return result;
    }

    /**
     * Normalizes a raw label line to just the class name.
     *
     * <p>Some label files (including the one extracted from EfficientNet-Lite0's
     * metadata) prefix each name with a 1-based index and a tab, e.g.
     * {@code "1\ttench"}. The line ORDER still matches the model's output index
     * (line 0 → output[0]); the printed number is purely cosmetic. We strip a
     * leading "&lt;digits&gt;&lt;whitespace&gt;" so the UI shows "tench". A plain
     * "tench" line is returned unchanged — so your own EUPE ViT labels.txt can be
     * in either format.
     */
    private static String cleanLabel(String raw) {
        String line = raw.trim();
        int i = 0;
        while (i < line.length() && Character.isDigit(line.charAt(i))) {
            i++;
        }
        if (i > 0 && i < line.length() && Character.isWhitespace(line.charAt(i))) {
            return line.substring(i).trim();
        }
        return line;
    }
}
