package com.example.sampleinf;

import android.graphics.Bitmap;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Image preprocessing helpers for the LiteRT path.
 *
 * <p>This is the single most important file to understand when porting a new
 * model (including the EUPE ViT): a neural network does not consume a JPEG or a
 * {@link Bitmap}; it consumes a precisely-shaped, precisely-normalized tensor of
 * numbers. "Preprocessing" is the act of turning pixels into exactly the tensor
 * the model was trained on. Get this wrong and the model still runs but returns
 * nonsense.
 *
 * <p>Three things must match the model's training recipe:
 * <ol>
 *   <li><b>Spatial size</b> — e.g. 224×224 for most ViTs and MobileNets.</li>
 *   <li><b>Value normalization</b> — how the 0..255 byte channels are mapped to
 *       floats. Common recipes:
 *       <ul>
 *         <li>[0,1]  : value / 255           → mean=0,     std=255</li>
 *         <li>[-1,1] : (value - 127.5)/127.5 → mean=127.5, std=127.5</li>
 *         <li>ImageNet per-channel mean/std (used by many PyTorch ViTs)</li>
 *       </ul></li>
 *   <li><b>Memory layout</b> — NHWC (TensorFlow default: [batch, H, W, channel])
 *       vs NCHW (PyTorch default: [batch, channel, H, W]). A model exported from
 *       PyTorch via litert-torch is often NCHW.</li>
 * </ol>
 *
 * <p>MediaPipe Tasks does all of this for you from the model's embedded metadata,
 * which is exactly why the {@link MediaPipeClassifier} has no preprocessing code.
 * Here we do it by hand so the mechanics are visible and tunable for the ViT.
 */
final class ImageUtils {

    private ImageUtils() {}

    /** Tensor memory layouts we support. See class docs. */
    enum Layout { NHWC, NCHW }

    /**
     * Scales {@code src} to exactly {@code width}×{@code height}. Uses bilinear
     * filtering. NOTE: this is a plain resize (it changes the aspect ratio). Many
     * training pipelines instead resize-shortest-side-then-center-crop; if your
     * model is sensitive to that, replicate it here.
     */
    static Bitmap resize(Bitmap src, int width, int height) {
        return Bitmap.createScaledBitmap(src, width, height, /* filter= */ true);
    }

    /**
     * Converts a resized bitmap to a FLOAT32 input buffer, normalizing each
     * channel as {@code (channelValue - mean) / std}.
     *
     * @param resized bitmap already at the model's input width/height
     * @param width   model input width
     * @param height  model input height
     * @param mean    subtracted from each 0..255 channel value
     * @param std     divides each (value - mean)
     * @param layout  NHWC (TensorFlow) or NCHW (PyTorch-style)
     * @return a direct, native-order ByteBuffer ready to hand to the interpreter
     */
    static ByteBuffer toFloat32Buffer(
            Bitmap resized, int width, int height, float mean, float std, Layout layout) {

        // 3 channels (RGB), 4 bytes per float32 value.
        final int bytes = width * height * 3 * 4;
        ByteBuffer buffer = ByteBuffer.allocateDirect(bytes);
        // TFLite/LiteRT expects native byte order for direct buffers.
        buffer.order(ByteOrder.nativeOrder());

        // Pull all pixels out once (each int is packed 0xAARRGGBB).
        int[] pixels = new int[width * height];
        resized.getPixels(pixels, 0, width, 0, 0, width, height);

        if (layout == Layout.NHWC) {
            // Interleaved: R,G,B, R,G,B, ... — the common TensorFlow layout.
            for (int i = 0; i < pixels.length; i++) {
                int p = pixels[i];
                buffer.putFloat((((p >> 16) & 0xFF) - mean) / std); // R
                buffer.putFloat((((p >> 8) & 0xFF) - mean) / std);  // G
                buffer.putFloat(((p & 0xFF) - mean) / std);         // B
            }
        } else {
            // Planar: all R values, then all G values, then all B values — the
            // common PyTorch (NCHW) layout.
            for (int c = 0; c < 3; c++) {
                int shift = (c == 0) ? 16 : (c == 1) ? 8 : 0;
                for (int i = 0; i < pixels.length; i++) {
                    int channel = (pixels[i] >> shift) & 0xFF;
                    buffer.putFloat((channel - mean) / std);
                }
            }
        }

        buffer.rewind(); // reset position to 0 so the interpreter reads from start
        return buffer;
    }

    /**
     * Like {@link #toFloat32Buffer} but with <b>per-channel</b> mean/std applied
     * to a [0,1]-scaled image: {@code ((channel/255) - mean[c]) / std[c]}.
     *
     * <p>This is the recipe most PyTorch models — including <b>EUPE ViT</b> — use:
     * scale to [0,1], then normalize with ImageNet statistics
     * {@code mean=(0.485,0.456,0.406)}, {@code std=(0.229,0.224,0.225)}.
     *
     * @param mean length-3 RGB means (in [0,1] units)
     * @param std  length-3 RGB std-devs (in [0,1] units)
     */
    static ByteBuffer toFloat32BufferPerChannel(
            Bitmap resized, int width, int height, float[] mean, float[] std, Layout layout) {

        ByteBuffer buffer = ByteBuffer.allocateDirect(width * height * 3 * 4);
        buffer.order(ByteOrder.nativeOrder());

        int[] pixels = new int[width * height];
        resized.getPixels(pixels, 0, width, 0, 0, width, height);

        if (layout == Layout.NHWC) {
            for (int pixel : pixels) {
                buffer.putFloat((((pixel >> 16) & 0xFF) / 255f - mean[0]) / std[0]); // R
                buffer.putFloat((((pixel >> 8) & 0xFF) / 255f - mean[1]) / std[1]);  // G
                buffer.putFloat(((pixel & 0xFF) / 255f - mean[2]) / std[2]);         // B
            }
        } else { // NCHW (the EUPE / PyTorch default): plane R, then G, then B
            for (int c = 0; c < 3; c++) {
                int shift = (c == 0) ? 16 : (c == 1) ? 8 : 0;
                for (int pixel : pixels) {
                    int channel = (pixel >> shift) & 0xFF;
                    buffer.putFloat((channel / 255f - mean[c]) / std[c]);
                }
            }
        }

        buffer.rewind();
        return buffer;
    }

    /**
     * Converts a resized bitmap to a UINT8 input buffer (no normalization). Use
     * this only for fully-quantized models whose input tensor type is UINT8 — the
     * model itself encodes the scale/zero-point. Layout is NHWC (the usual case
     * for quantized vision models).
     */
    static ByteBuffer toUint8Buffer(Bitmap resized, int width, int height) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(width * height * 3);
        buffer.order(ByteOrder.nativeOrder());

        int[] pixels = new int[width * height];
        resized.getPixels(pixels, 0, width, 0, 0, width, height);
        for (int p : pixels) {
            buffer.put((byte) ((p >> 16) & 0xFF)); // R
            buffer.put((byte) ((p >> 8) & 0xFF));  // G
            buffer.put((byte) (p & 0xFF));         // B
        }
        buffer.rewind();
        return buffer;
    }

    /**
     * Numerically-stable softmax over {@code logits}, returned as a new array of
     * probabilities that sum to 1. Applied when a model outputs raw logits rather
     * than already-normalized probabilities.
     */
    static float[] softmax(float[] logits) {
        float max = Float.NEGATIVE_INFINITY;
        for (float v : logits) max = Math.max(max, v);

        float sum = 0f;
        float[] out = new float[logits.length];
        for (int i = 0; i < logits.length; i++) {
            out[i] = (float) Math.exp(logits[i] - max);
            sum += out[i];
        }
        if (sum > 0f) {
            for (int i = 0; i < out.length; i++) out[i] /= sum;
        }
        return out;
    }
}
