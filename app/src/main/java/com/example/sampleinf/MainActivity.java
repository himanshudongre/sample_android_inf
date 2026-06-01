package com.example.sampleinf;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.Bundle;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The app's single screen.
 *
 * <p>Responsibilities, kept intentionally small:
 * <ul>
 *   <li>load the image to classify (a bundled asset, or a synthetic fallback),</li>
 *   <li>on a button press, run the chosen {@link InferenceBackend} OFF the UI
 *       thread (model inference must never block the main thread), and</li>
 *   <li>display the results (and any setup errors) back on the UI thread.</li>
 * </ul>
 *
 * <p>The UI talks only to the {@link InferenceBackend} interface, so it is
 * identical regardless of whether LiteRT or MediaPipe (or, later, EUPE ViT) is
 * doing the work.
 */
public final class MainActivity extends Activity {

    /** Optional bundled test image; created by tools/download_model.sh. */
    private static final String SAMPLE_IMAGE_ASSET = "sample.jpg";

    private ImageView imageView;
    private TextView statusText;
    private TextView resultsText;
    private Button litertButton;
    private Button mediapipeButton;

    /** Single background thread for inference work. */
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private Bitmap inputBitmap;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        imageView = findViewById(R.id.image_view);
        statusText = findViewById(R.id.status_text);
        resultsText = findViewById(R.id.results_text);
        litertButton = findViewById(R.id.btn_litert);
        mediapipeButton = findViewById(R.id.btn_mediapipe);

        inputBitmap = loadSampleImage();
        imageView.setImageBitmap(inputBitmap);

        // Each button selects a backend; both go through the same code path.
        litertButton.setOnClickListener(v -> runInference(Backend.LITERT));
        mediapipeButton.setOnClickListener(v -> runInference(Backend.MEDIAPIPE));
    }

    private enum Backend { LITERT, MEDIAPIPE }

    /**
     * Builds the requested backend, classifies the image, and shows the result.
     * All heavy work happens on {@link #executor}; only UI updates touch the main
     * thread (via {@link #runOnUiThread}).
     */
    private void runInference(Backend backend) {
        setBusy(true);
        statusText.setText(String.format(Locale.US, "Running %s…", backend));

        executor.execute(() -> {
            String resultText;
            String status;
            try {
                // try-with-resources guarantees native handles are freed.
                try (InferenceBackend engine = createBackend(backend)) {
                    long startNs = System.nanoTime();
                    List<Classification> results = engine.classify(inputBitmap);
                    long elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;

                    StringBuilder sb = new StringBuilder();
                    sb.append(engine.name())
                      .append("  (")
                      .append(elapsedMs)
                      .append(" ms)\n\n");
                    if (results.isEmpty()) {
                        sb.append("(no results)");
                    } else {
                        for (Classification c : results) {
                            sb.append(c).append('\n');
                        }
                    }
                    resultText = sb.toString();
                    status = "Done.";
                }
            } catch (Exception e) {
                // The most common cause is a missing model asset.
                resultText = "ERROR: " + e.getMessage()
                        + "\n\nIf the model is missing, run:\n  ./tools/download_model.sh\n"
                        + "then rebuild and reinstall the app.";
                status = "Failed.";
            }

            final String finalResult = resultText;
            final String finalStatus = status;
            runOnUiThread(() -> {
                resultsText.setText(finalResult);
                statusText.setText(finalStatus);
                setBusy(false);
            });
        });
    }

    private InferenceBackend createBackend(Backend backend) throws Exception {
        switch (backend) {
            case LITERT:
                return new LiteRtClassifier(this);
            case MEDIAPIPE:
                return new MediaPipeClassifier(this);
            default:
                throw new IllegalArgumentException("Unknown backend: " + backend);
        }
    }

    private void setBusy(boolean busy) {
        litertButton.setEnabled(!busy);
        mediapipeButton.setEnabled(!busy);
    }

    /**
     * Loads {@code sample.jpg} from assets if present; otherwise synthesizes a
     * colourful placeholder so the app always has something to classify (handy
     * before you've run the download script). A synthetic image won't classify to
     * anything meaningful — it just proves the pipeline runs end to end.
     */
    private Bitmap loadSampleImage() {
        try (InputStream is = getAssets().open(SAMPLE_IMAGE_ASSET)) {
            Bitmap bmp = BitmapFactory.decodeStream(is);
            if (bmp != null) {
                return bmp;
            }
        } catch (Exception ignored) {
            // Fall through to the synthetic image.
        }
        return createSyntheticBitmap(224, 224);
    }

    /** Draws a simple gradient + circle so the placeholder isn't a blank box. */
    private Bitmap createSyntheticBitmap(int width, int height) {
        Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);

        Paint bg = new Paint();
        bg.setShader(new LinearGradient(
                0, 0, width, height,
                Color.rgb(80, 160, 220), Color.rgb(230, 120, 60),
                Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, width, height, bg);

        Paint circle = new Paint(Paint.ANTI_ALIAS_FLAG);
        circle.setColor(Color.WHITE);
        canvas.drawCircle(width / 2f, height / 2f, width / 4f, circle);
        return bmp;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }
}
