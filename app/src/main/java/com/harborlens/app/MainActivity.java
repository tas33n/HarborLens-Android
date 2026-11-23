package com.harborlens.app;

import android.Manifest;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.RectF;
import android.provider.MediaStore;
import android.webkit.CookieManager;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.camera.core.UseCaseGroup;
import androidx.camera.view.transform.CoordinateTransform;
import androidx.camera.view.transform.ImageProxyTransformFactory;
import androidx.camera.view.transform.OutputTransform;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;
import java.util.regex.Matcher;

public final class MainActivity extends ComponentActivity {
    private static final int INK = 0xff241d37, MUTED = 0xff625a73, BG = 0xfff8f5ff;
    private static final int PURPLE = 0xff5438a8, TINT = 0xffeae0ff;
    private static final String SAMPLE = "TCKU3877226";
    private static final String BASE = "Shipping Bill List";
    private static final int GREEN = 0xff147d51, RED = 0xffcc2639;

    private static final class Scan {
        final String code;
        Scan(String code) { this.code = code; }
    }

    private final ArrayList<Scan> scans = new ArrayList<>();
    private final TextRecognizer recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
    private LinearLayout root, content;
    private PreviewView preview;
    private TextView cameraMessage, counts, lastSaved;
    private ProcessCameraProvider provider;
    private Camera camera;
    private int tab, generation, stableFrames, blankFrames;
    private String candidate = "", latched = "", duplicate;
    private Reticle reticle;
    private LinearLayout duplicatePanel;
    private TextView duplicateMessage;
    private final java.util.concurrent.ExecutorService exportExecutor = java.util.concurrent.Executors.newSingleThreadExecutor();
    private final java.util.HashMap<String, String[]> resolved = new java.util.HashMap<>();
    private WorkManager work;
    private android.content.SharedPreferences results;
    private boolean torch, modal;

    private final androidx.activity.result.ActivityResultLauncher<String> permission =
        registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            if (tab == 0) {
                if (granted) render();
                else cameraMessage.setText("Camera permission needed");
            }
        });

    private final androidx.activity.result.ActivityResultLauncher<String> wordDestination =
        registerForActivityResult(new ActivityResultContracts.CreateDocument(ShippingExport.WORD),
            uri -> saveCopy(uri, true));
    private final androidx.activity.result.ActivityResultLauncher<String> excelDestination =
        registerForActivityResult(new ActivityResultContracts.CreateDocument(ShippingExport.EXCEL),
            uri -> saveCopy(uri, false));

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            | (android.os.Build.VERSION.SDK_INT >= 26 ? View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : 0));
        results = getSharedPreferences(LookupWorker.STORE, MODE_PRIVATE);
        work = WorkManager.getInstance(this);
        load();
        render();
        work.getWorkInfosByTagLiveData(LookupWorker.TAG).observe(this, ignored -> {
            boolean changed = refreshMatches();
            if (tab != 0 && !modal && (changed || tab == 1)) render();
            if (tab == 0) updateCounts();
            if (changed) saveReports(null, false);
        });
        saveReports(null, false);
        for (Scan scan : scans) {
            if (!resolved.containsKey(scan.code) && !results.contains(scan.code + "-error")) queue(scan.code);
        }
    }

    @Override protected void onDestroy() {
        stopCamera();
        recognizer.close();
        exportExecutor.shutdown();
        super.onDestroy();
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private GradientDrawable shape(int color, int radius) {
        GradientDrawable result = new GradientDrawable();
        result.setColor(color);
        result.setCornerRadius(dp(radius));
        return result;
    }
    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return view;
    }
    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }
    private void space(LinearLayout target, int height) {
        target.addView(new View(this), new LinearLayout.LayoutParams(1, dp(height)));
    }
    private Button button(String label, boolean primary, Runnable click) {
        Button result = new Button(this);
        result.setAllCaps(false);
        result.setText(label);
        result.setTextColor(primary ? Color.WHITE : PURPLE);
        result.setBackground(new android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(0x335438a8),
            shape(primary ? PURPLE : TINT, 28), null));
        result.setMinHeight(dp(48));
        result.setPadding(dp(12), dp(7), dp(12), dp(7));
        result.setOnClickListener(v -> click.run());
        return result;
    }
    private LinearLayout card() {
        LinearLayout box = column();
        box.setBackground(shape(Color.WHITE, 25));
        box.setPadding(dp(17), dp(15), dp(17), dp(15));
        content.addView(box, new LinearLayout.LayoutParams(-1, -2));
        return box;
    }

    private void render() {
        stopCamera();
        root = column();
        root.setBackgroundColor(BG);
        root.setPadding(dp(18), dp(12), dp(18), dp(10));
        setContentView(root);
        root.addView(text("BSCA SB Scan", 15, PURPLE, true));
        space(root, 10);
        String[] headings = {"Scan documents", "Documents", "Shipping bill list", "Settings"};
        root.addView(text(headings[tab], 34, INK, true));
        space(root, 5);
        root.addView(text("BS Cargo Agency Ltd.", 13, MUTED, false));
        space(root, 13);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        content = column();
        scroll.addView(content);
        if (tab == 0) renderScanner();
        else if (tab == 1) renderDocuments();
        else if (tab == 2) renderMemo();
        else renderSettings();

        space(root, 10);
        LinearLayout nav = new LinearLayout(this);
        root.addView(nav);
        String[] labels = {"Scan", "Docs", "Reports", "Settings"};
        for (int i = 0; i < labels.length; i++) {
            final int selected = i;
            Button item = button(labels[i], i == tab, () -> {
                if (duplicate != null) { toast("Choose Count document or Ignore first"); return; }
                if (tab != selected) { tab = selected; render(); }
            });
            item.setSelected(i == tab);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(48), 1);
            if (i > 0) params.leftMargin = dp(7);
            nav.addView(item, params);
        }
    }

    private void renderScanner() {
        FrameLayout cameraPanel = new FrameLayout(this);
        cameraPanel.setBackground(shape(INK, 26));
        cameraPanel.setClipToOutline(true);
        content.addView(cameraPanel, new LinearLayout.LayoutParams(-1, dp(296)));
        preview = new PreviewView(this);
        preview.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        preview.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        cameraPanel.addView(preview, new FrameLayout.LayoutParams(-1, -1));
        reticle = new Reticle();
        cameraPanel.addView(reticle, new FrameLayout.LayoutParams(-1, -1));

        cameraMessage = text("Align the container number", 13, Color.WHITE, true);
        cameraMessage.setGravity(Gravity.CENTER);
        cameraMessage.setBackground(shape(0xe0241d37, 19));
        FrameLayout.LayoutParams msg = new FrameLayout.LayoutParams(-1, dp(40), Gravity.BOTTOM);
        msg.setMargins(dp(14), 0, dp(14), dp(13));
        cameraPanel.addView(cameraMessage, msg);

        Button light = button("Torch", false, () -> {
            if (camera == null || !camera.getCameraInfo().hasFlashUnit()) return;
            torch = !torch;
            camera.getCameraControl().enableTorch(torch);
        });
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, dp(46), Gravity.TOP | Gravity.END);
        lp.setMargins(0, dp(9), dp(9), 0);
        cameraPanel.addView(light, lp);

        space(content, 9);
        duplicatePanel = column();
        duplicatePanel.setPadding(dp(12), dp(9), dp(12), dp(9));
        duplicatePanel.setBackground(shape(0xffffe8eb, 18));
        duplicateMessage = text("", 14, RED, true);
        duplicatePanel.addView(duplicateMessage);
        LinearLayout choices = new LinearLayout(this);
        duplicatePanel.addView(choices);
        choices.addView(button("Count document", true, () -> finishDuplicate(true)), new LinearLayout.LayoutParams(0, -2, 1));
        choices.addView(button("Ignore", false, () -> finishDuplicate(false)), new LinearLayout.LayoutParams(0, -2, 1));
        duplicatePanel.setVisibility(View.GONE);
        content.addView(duplicatePanel);
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraMessage.setText("Enable the rear camera to scan");
            content.addView(button("Enable camera", true, () -> permission.launch(Manifest.permission.CAMERA)));
            space(content, 6);
        } else {
            startCamera();
        }
        LinearLayout actions = new LinearLayout(this);
        content.addView(actions);
        actions.addView(button("Enter number", true, this::manual), new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams next = new LinearLayout.LayoutParams(0, -2, 1);
        next.leftMargin = dp(8);
        actions.addView(button("Scan same again", false, () -> {
            if (duplicate != null) return;
            if (ContainerNumber.valid(latched)) add(latched);
            else toast("Scan a container first");
        }), next);
        space(content, 11);
        LinearLayout batch = card();
        batch.addView(text("Batch progress", 21, INK, true));
        space(batch, 5);
        counts = text("", 16, PURPLE, true);
        batch.addView(counts);
        lastSaved = text("", 13, MUTED, false);
        batch.addView(lastSaved);
        updateCounts();
    }

    private void updateCounts() {
        int matched = 0;
        for (Scan scan : scans) if (resolved.containsKey(scan.code)) matched++;
        counts.setText(scans.size() + " saved   /   " + matched + " matched   /   " + (scans.size() - matched) + " pending");
        lastSaved.setText(scans.isEmpty() ? "Scans are stored on this phone."
            : "Last saved · " + scans.get(scans.size() - 1).code);
    }

    private void stopCamera() {
        generation++;
        if (provider != null) provider.unbindAll();
        camera = null;
        torch = false;
        candidate = "";
        latched = "";
        stableFrames = 0;
        blankFrames = 0;
    }

    @SuppressLint("UnsafeOptInUsageError")
    private void startCamera() {
        if (preview.getWidth() == 0) {
            PreviewView expected = preview;
            expected.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                @Override public void onLayoutChange(View view, int l, int t, int r, int b,
                    int oldL, int oldT, int oldR, int oldB) {
                    if (r <= l || b <= t) return;
                    expected.removeOnLayoutChangeListener(this);
                    if (tab == 0 && preview == expected && !isDestroyed()) startCamera();
                }
            });
            return;
        }
        int token = ++generation;
        var future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            if (token != generation) return;
            try {
                provider = future.get();
                Preview stream = new Preview.Builder().build();
                stream.setSurfaceProvider(preview.getSurfaceProvider());
                ImageAnalysis analysis = new ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build();
                analysis.setAnalyzer(ContextCompat.getMainExecutor(this), frame -> analyze(frame, token));
                provider.unbindAll();
                var viewport = preview.getViewPort();
                if (viewport != null) {
                    camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA,
                        new UseCaseGroup.Builder().setViewPort(viewport).addUseCase(stream).addUseCase(analysis).build());
                } else {
                    camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, stream, analysis);
                }
                cameraMessage.setText("Align the container number");
            } catch (Exception unavailable) {
                cameraMessage.setText("Camera unavailable · use manual entry");
            }
        }, ContextCompat.getMainExecutor(this));
    }

    @SuppressLint("UnsafeOptInUsageError")
    private void analyze(ImageProxy frame, int token) {
        android.media.Image image = frame.getImage();
        if (token != generation || image == null || duplicate != null || modal || !hasWindowFocus()) {
            frame.close();
            return;
        }
        int rotation = frame.getImageInfo().getRotationDegrees();
        ImageProxyTransformFactory factory = new ImageProxyTransformFactory();
        factory.setUsingRotationDegrees(true);
        OutputTransform source = factory.getOutputTransform(frame);
        try {
            recognizer.process(InputImage.fromMediaImage(image, rotation))
                .addOnSuccessListener(ContextCompat.getMainExecutor(this), result -> {
                    if (token == generation && duplicate == null && !modal && hasWindowFocus()) recognize(result, source);
                })
                .addOnFailureListener(ContextCompat.getMainExecutor(this), error -> {
                    if (token == generation) cameraMessage.setText("OCR unavailable · use manual entry");
                })
                .addOnCompleteListener(ContextCompat.getMainExecutor(this), task -> frame.close());
        } catch (RuntimeException unavailable) {
            frame.close();
            if (token == generation) cameraMessage.setText("OCR unavailable · use manual entry");
        }
    }

    private void recognize(Text result, OutputTransform source) {
        OutputTransform target = preview.getOutputTransform();
        if (target == null) return;
        CoordinateTransform transform = new CoordinateTransform(source, target);
        String hit = "";
        RectF hitBounds = null;
        double proximity = Double.MAX_VALUE;
        for (Text.TextBlock block : result.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                String value = line.getText().toUpperCase(Locale.ROOT);
                Matcher matcher = ContainerNumber.CANDIDATE.matcher(value);
                while (matcher.find()) {
                    String code = ContainerNumber.normalize(matcher.group());
                    if (!ContainerNumber.valid(code)) continue;
                    RectF bounds = boundsFor(line, value, matcher.start(), matcher.end());
                    if (bounds == null) continue;
                    transform.mapRect(bounds);
                    if (!RectF.intersects(bounds, new RectF(0, 0, preview.getWidth(), preview.getHeight()))) continue;
                    double distance = Math.abs(bounds.centerY() - preview.getHeight() / 2f);
                    if (distance >= proximity) continue;
                    hit = code;
                    hitBounds = bounds;
                    proximity = distance;
                }
            }
        }
        if (hit.isEmpty()) {
            stableFrames = 0;
            if (++blankFrames >= 3) {
                latched = "";
                reticle.mark(null, GREEN);
                cameraMessage.setText("Aim at a container number");
                cameraMessage.setBackground(shape(0xe0241d37, 19));
            }
            return;
        }
        blankFrames = 0;
        if (!hit.equals(candidate)) { candidate = hit; stableFrames = 0; }
        if (++stableFrames < 2) return;
        if (!hit.equals(latched)) {
            latched = hit;
            reticle.mark(hitBounds, isRepeat(hit) ? RED : GREEN);
            add(hit);
        } else if (reticle.highlight != null) {
            reticle.mark(hitBounds, reticle.color);
        }
    }

    private RectF boundsFor(Text.Line line, String value, int start, int end) {
        RectF bounds = null;
        int cursor = 0;
        for (Text.Element element : line.getElements()) {
            String part = element.getText().toUpperCase(Locale.ROOT);
            int index = value.indexOf(part, cursor);
            if (index < 0) continue;
            cursor = index + part.length();
            Rect box = element.getBoundingBox();
            if (box != null && index < end && cursor > start) {
                if (bounds == null) bounds = new RectF(box);
                else bounds.union(box.left, box.top, box.right, box.bottom);
            }
        }
        return bounds;
    }

    private void manual() {
        if (duplicate != null) return;
        EditText input = new EditText(this);
        input.setSingleLine();
        input.setHint(SAMPLE);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        LinearLayout field = column();
        field.setPadding(dp(23), 0, dp(23), 0);
        field.addView(input);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Container number").setView(field)
            .setNegativeButton("Cancel", null).setPositiveButton("Save", null).create();
        show(dialog);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String value = ContainerNumber.normalize(input.getText().toString());
            if (!ContainerNumber.valid(value)) { input.setError("Check the container number and final digit"); return; }
            ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(input.getWindowToken(), 0);
            dialog.dismiss();
            add(value);
        });
    }

    private void show(AlertDialog dialog) {
        modal = true;
        dialog.setOnDismissListener(d -> modal = false);
        dialog.show();
    }

    private boolean isRepeat(String code) {
        for (Scan scan : scans) if (scan.code.equals(code)) return true;
        return false;
    }

    private void add(String code) {
        cameraMessage.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
        if (isRepeat(code)) {
            duplicate = code;
            cameraMessage.setText("Repeat detected · " + code);
            cameraMessage.setBackground(shape(RED, 19));
            duplicateMessage.setText(code + " is already listed. Count this if it is a separate document.");
            duplicatePanel.setVisibility(View.VISIBLE);
        } else {
            save(code);
        }
    }

    private void finishDuplicate(boolean count) {
        String code = duplicate;
        duplicate = null;
        duplicatePanel.setVisibility(View.GONE);
        if (count) save(code);
        else {
            cameraMessage.setText("Ignored · " + code);
            cameraMessage.setBackground(shape(0xe0241d37, 19));
            reticle.mark(null, GREEN);
        }
    }

    private void save(String code) {
        scans.add(new Scan(code));
        persist();
        updateCounts();
        cameraMessage.setText("Document counted · " + code);
        cameraMessage.setBackground(shape(GREEN, 19));
        if (!resolved.containsKey(code)) queue(code);
        else saveReports(null, false);
    }

    private void queue(String code) {
        var request = new OneTimeWorkRequest.Builder(LookupWorker.class)
            .setInputData(new Data.Builder().putString("container", code).build())
            .setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .addTag(LookupWorker.TAG).build();
        work.enqueueUniqueWork("cpa-" + code, ExistingWorkPolicy.KEEP, request);
    }

    private boolean refreshMatches() {
        int old = resolved.size();
        for (Scan scan : scans) {
            String raw = results.getString(scan.code, null);
            if (raw == null || resolved.containsKey(scan.code)) continue;
            try {
                JSONArray array = new JSONArray(raw);
                String[] row = new String[7];
                for (int i = 0; i < row.length; i++) row[i] = array.getString(i);
                resolved.put(scan.code, row);
            } catch (JSONException ignored) { }
        }
        return resolved.size() != old;
    }

    private void retryPending() {
        for (Scan scan : scans) {
            if (resolved.containsKey(scan.code)) continue;
            results.edit().remove(scan.code + "-error").apply();
            queue(scan.code);
        }
        render();
    }

    private void renderDocuments() {
        content.addView(button("Retry CPA", false, this::retryPending));
        space(content, 8);
        if (scans.isEmpty()) card().addView(text("Scanned documents will appear here.", 15, MUTED, false));
        for (int i = scans.size() - 1; i >= 0; i--) {
            Scan scan = scans.get(i);
            LinearLayout row = card();
            TextView number = text(scan.code, 19, INK, true);
            number.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            row.addView(number);
            String[] sailing = resolved.get(scan.code);
            row.addView(text(sailing == null ? results.getString(scan.code + "-error", "Waiting for CPA") :
                sailing[1] + " · " + sailing[2] + " · " + sailing[3], 14, MUTED, false));
            space(row, 7);
            row.addView(button("Remove document", false, () -> confirmRemove(scan)));
            space(content, 8);
        }
    }

    private void confirmRemove(Scan scan) {
        show(new AlertDialog.Builder(this).setTitle("Remove this document?")
            .setMessage(scan.code).setNegativeButton("Cancel", null)
            .setPositiveButton("Remove", (d, w) -> { scans.remove(scan); persist(); render(); saveReports(null, false); }).create());
    }

    private ArrayList<String[]> reportRows() {
        java.util.LinkedHashMap<String, String[]> rows = new java.util.LinkedHashMap<>();
        for (Scan scan : scans) {
            String[] match = resolved.get(scan.code);
            if (match == null) continue;
            String[] row = rows.get(ShippingExport.key(match));
            if (row == null) rows.put(ShippingExport.key(match), match.clone());
            else row[5] = Integer.toString(Integer.parseInt(row[5]) + 1);
        }
        return new ArrayList<>(rows.values());
    }

    private void renderMemo() {
        int matched = 0;
        for (Scan scan : scans) if (resolved.containsKey(scan.code)) matched++;
        content.addView(text(matched + " matched · " + (scans.size() - matched) + " awaiting CPA", 15, PURPLE, true));
        space(content, 8);
        content.addView(text("Word and Excel are saved in Downloads/BSCA SB Scan", 14, MUTED, false));
        space(content, 10);
        content.addView(button("Save reports now", true, () -> saveReports(null, true)));
        space(content, 7);
        content.addView(button("Save Word copy…", false, () -> wordDestination.launch(BASE + ".docx")));
        space(content, 7);
        content.addView(button("Save Excel copy…", false, () -> excelDestination.launch(BASE + ".xlsx")));
        space(content, 7);
        content.addView(button("Share Word (.docx)", false, () -> saveReports(ShippingExport.WORD, true)));
        space(content, 7);
        content.addView(button("Share Excel (.xlsx)", false, () -> saveReports(ShippingExport.EXCEL, true)));
        space(content, 12);
        for (String[] sailing : reportRows()) {
            LinearLayout row = card();
            row.addView(text(sailing[1], 21, INK, true));
            row.addView(text("VOY " + sailing[2] + " · EXP. ROT. " + sailing[3], 14, MUTED, false));
            row.addView(text("ETD " + sailing[4] + " · +" + sailing[5] + " docs · " + sailing[6], 14, PURPLE, true));
            space(content, 8);
        }
    }

    private void saveReports(String share, boolean notify) {
        var snapshot = reportRows();
        exportExecutor.execute(() -> {
            try {
                var files = ShippingExport.create(this, snapshot);
                Uri word = saveDownload("word", BASE + ".docx", ShippingExport.WORD, files.word);
                Uri excel = saveDownload("excel", BASE + ".xlsx", ShippingExport.EXCEL, files.excel);
                if (notify) runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (share == null) toast("Saved in Downloads/BSCA SB Scan");
                    else {
                        Uri uri = ShippingExport.WORD.equals(share) ? word : excel;
                        Intent send = new Intent(Intent.ACTION_SEND).setType(share)
                            .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        send.setClipData(ClipData.newRawUri(BASE, uri));
                        startActivity(Intent.createChooser(send, "Share shipping bill list"));
                    }
                });
            } catch (Exception failure) {
                runOnUiThread(() -> toast("Could not save the reports to Downloads"));
            }
        });
    }

    private void saveCopy(Uri uri, boolean word) {
        if (uri == null) return;
        var snapshot = reportRows();
        exportExecutor.execute(() -> {
            try {
                var files = ShippingExport.create(this, snapshot);
                try (OutputStream output = getContentResolver().openOutputStream(uri, "rwt")) {
                    if (output == null) throw new IOException("Could not open export destination");
                    output.write(word ? files.word : files.excel);
                }
                runOnUiThread(() -> toast("Shipping bill list saved"));
            } catch (Exception failure) {
                runOnUiThread(() -> toast("Could not save the shipping bill list"));
            }
        });
    }

    private Uri saveDownload(String key, String name, String mime, byte[] data) throws IOException {
        var preferences = getSharedPreferences("reports", MODE_PRIVATE);
        var resolver = getContentResolver();
        String previous = preferences.getString(key, "");
        if (!previous.isEmpty()) {
            Uri uri = Uri.parse(previous);
            try (OutputStream out = resolver.openOutputStream(uri, "rwt")) {
                if (out != null) { out.write(data); return uri; }
            } catch (java.io.FileNotFoundException | SecurityException missing) {
                preferences.edit().remove(key).apply();
            }
        }
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/BSCA SB Scan");
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IOException("Downloads unavailable");
        try {
            try (OutputStream out = resolver.openOutputStream(uri, "w")) {
                if (out == null) throw new IOException("Download stream unavailable");
                out.write(data);
            }
            values.clear();
            values.put(MediaStore.MediaColumns.IS_PENDING, 0);
            resolver.update(uri, values, null, null);
            preferences.edit().putString(key, uri.toString()).commit();
            return uri;
        } catch (IOException | RuntimeException failure) {
            resolver.delete(uri, null, null);
            throw failure;
        }
    }

    private void renderSettings() {
        LinearLayout account = card();
        account.addView(text("CPA Port Community System", 20, INK, true));
        account.addView(text("Sign in on CPA’s own page. BSCA SB Scan reuses the session while it is valid.", 14, MUTED, false));
        space(account, 7);
        account.addView(button("Sign in to CPA", true, this::signIn));
        space(account, 7);
        account.addView(button("Clear CPA session", false, () ->
            CookieManager.getInstance().removeAllCookies(done -> {
                CookieManager.getInstance().flush();
                toast("CPA session cleared");
            })));
        space(content, 10);
        LinearLayout about = card();
        about.addView(text("About BSCA SB Scan", 20, INK, true));
        about.addView(text("© 2026 BS Cargo Agency Ltd.\nAuthor · tas33n\nVersion 0.2 (2) · 23 November 2026", 14, MUTED, false));
        space(about, 8);
        about.addView(button("Source repository", false, () -> openLink("https://github.com/tas33n/HarborLens-Android")));
        space(about, 6);
        about.addView(button("Contact tas33n", false, () -> openLink("https://github.com/tas33n")));
    }

    private void openLink(String url) {
        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void signIn() {
        WebView web = new WebView(this);
        web.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_YES);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setAllowFileAccess(false);
        web.getSettings().setAllowContentAccess(false);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, android.webkit.WebResourceRequest request) {
                Uri uri = request.getUrl();
                return !"https".equals(uri.getScheme()) || !"cpatos.gov.bd".equalsIgnoreCase(uri.getHost());
            }
        });
        web.loadUrl(Cpa.LOGIN);
        AlertDialog login = new AlertDialog.Builder(this).setTitle("Sign in on CPA")
            .setView(web).setNegativeButton("Cancel", null)
            .setPositiveButton("Retry pending lookups", (d, w) -> {
                CookieManager.getInstance().flush();
                retryPending();
            }).create();
        show(login);
        login.setOnDismissListener(d -> { modal = false; web.destroy(); });
        if (login.getWindow() != null) login.getWindow().setLayout(-1, -1);
    }

    private void persist() {
        JSONArray data = new JSONArray();
        try {
            for (Scan scan : scans) data.put(new JSONObject().put("code", scan.code));
            if (!getPreferences(MODE_PRIVATE).edit().putString("scans", data.toString()).commit()) {
                toast("Could not save the batch to this device");
            }
        } catch (JSONException invalid) { toast("Could not save the batch"); }
    }

    private void load() {
        try {
            JSONArray data = new JSONArray(getPreferences(MODE_PRIVATE).getString("scans", "[]"));
            for (int i = 0; i < data.length(); i++) {
                JSONObject row = data.optJSONObject(i);
                if (row == null) continue;
                String code = row.optString("code");
                if (ContainerNumber.valid(code)) scans.add(new Scan(code));
            }
            refreshMatches();
        } catch (JSONException invalid) { toast("Could not read the previous batch"); }
    }

    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); }

    private final class Reticle extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private RectF highlight;
        private int color = GREEN;
        Reticle() { super(MainActivity.this); }
        void mark(RectF bounds, int tint) {
            highlight = bounds == null ? null : new RectF(bounds);
            color = tint;
            invalidate();
        }
        @Override protected void onDraw(Canvas canvas) {
            float left = dp(24), right = getWidth() - dp(24);
            float top = getHeight() / 2f - dp(33), bottom = getHeight() / 2f + dp(33);
            paint.setColor(0x60241d37);
            canvas.drawRect(0, 0, getWidth(), top, paint);
            canvas.drawRect(0, bottom, getWidth(), getHeight(), paint);
            canvas.drawRect(0, top, left, bottom, paint);
            canvas.drawRect(right, top, getWidth(), bottom, paint);
            paint.setColor(0xffe1d3ff);
            paint.setStrokeWidth(dp(3));
            float edge = dp(17);
            for (float x : new float[]{left, right}) {
                for (float y : new float[]{top, bottom}) {
                    canvas.drawLine(x, y, x + (x == left ? edge : -edge), y, paint);
                    canvas.drawLine(x, y, x, y + (y == top ? edge : -edge), paint);
                }
            }
            if (highlight != null) {
                RectF outline = new RectF(highlight);
                outline.inset(-dp(5), -dp(4));
                paint.setColor((color & 0x00ffffff) | 0x44000000);
                canvas.drawRoundRect(outline, dp(7), dp(7), paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setColor(color);
                canvas.drawRoundRect(outline, dp(7), dp(7), paint);
                canvas.drawLine(outline.left, outline.bottom, outline.right, outline.bottom, paint);
                paint.setStyle(Paint.Style.FILL);
            }
        }
    }
}
