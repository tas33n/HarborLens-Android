package com.harborlens.app;

import android.Manifest;
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
    private static final String SAMPLE = "TCKU3877226", MLO = "BTB";
    private static final String[][] SAILINGS = {
        {"SINAR SORONG", "302S", "2026/5026", "2026-11-24"},
        {"HR RHEA", "108S", "2026/2720", "2026-11-22"},
        {"HR BALU", "075S", "2026/2739", "2026-11-23"}
    };

    private static final class Scan {
        final String code;
        int sailing;
        Scan(String code, int sailing) { this.code = code; this.sailing = sailing; }
    }

    private final ArrayList<Scan> scans = new ArrayList<>();
    private final TextRecognizer recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
    private LinearLayout root, content;
    private PreviewView preview;
    private TextView cameraMessage, counts, lastSaved;
    private ProcessCameraProvider provider;
    private Camera camera;
    private int tab, generation, stableFrames, blankFrames;
    private String candidate = "", latched = "", exportText;
    private boolean torch, modal;

    private final androidx.activity.result.ActivityResultLauncher<String> permission =
        registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            if (tab == 0) {
                if (granted) render();
                else cameraMessage.setText("Camera permission needed");
            }
        });

    private final androidx.activity.result.ActivityResultLauncher<String> csvDestination =
        registerForActivityResult(new ActivityResultContracts.CreateDocument("text/csv"), this::writeCsv);

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            | (android.os.Build.VERSION.SDK_INT >= 26 ? View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : 0));
        load();
        render();
    }

    @Override protected void onDestroy() {
        stopCamera();
        recognizer.close();
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
        root.addView(text("HarborLens   /   BTB", 15, PURPLE, true));
        space(root, 10);
        String[] headings = {"Scan documents", "Documents", "Export memo"};
        root.addView(text(headings[tab], 34, INK, true));
        space(root, 5);
        root.addView(text("Chattogram  •  CPA demo matches", 13, MUTED, false));
        space(root, 13);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        content = column();
        scroll.addView(content);
        if (tab == 0) renderScanner();
        else if (tab == 1) renderDocuments();
        else renderMemo();

        space(root, 10);
        LinearLayout nav = new LinearLayout(this);
        root.addView(nav);
        String[] labels = {"Scan", "Documents", "Memo"};
        for (int i = 0; i < labels.length; i++) {
            final int selected = i;
            Button item = button(labels[i], i == tab, () -> {
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
        cameraPanel.addView(new Reticle(), new FrameLayout.LayoutParams(-1, -1));

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
        for (Scan scan : scans) if (scan.sailing >= 0) matched++;
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
                camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, stream, analysis);
                cameraMessage.setText("Align the container number");
            } catch (Exception unavailable) {
                cameraMessage.setText("Camera unavailable · use manual entry");
            }
        }, ContextCompat.getMainExecutor(this));
    }

    @SuppressLint("UnsafeOptInUsageError")
    private void analyze(ImageProxy frame, int token) {
        android.media.Image image = frame.getImage();
        if (token != generation || image == null || modal || !hasWindowFocus()) {
            frame.close();
            return;
        }
        int rotation = frame.getImageInfo().getRotationDegrees();
        int height = rotation == 90 || rotation == 270 ? frame.getWidth() : frame.getHeight();
        try {
            recognizer.process(InputImage.fromMediaImage(image, rotation))
                .addOnSuccessListener(ContextCompat.getMainExecutor(this), result -> {
                    if (token == generation && !modal && hasWindowFocus()) recognize(result, height);
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

    private void recognize(Text result, int frameHeight) {
        String hit = "";
        double proximity = Double.MAX_VALUE;
        for (Text.TextBlock block : result.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                Rect bounds = line.getBoundingBox();
                if (bounds == null) continue;
                double distance = Math.abs(bounds.exactCenterY() / frameHeight - 0.5);
                if (distance > 0.17 || distance >= proximity) continue;
                Matcher matcher = ContainerNumber.CANDIDATE.matcher(line.getText().toUpperCase(Locale.ROOT));
                while (matcher.find()) {
                    String value = ContainerNumber.normalize(matcher.group());
                    if (ContainerNumber.valid(value)) {
                        hit = value;
                        proximity = distance;
                        break;
                    }
                }
            }
        }
        if (hit.isEmpty()) {
            stableFrames = 0;
            if (++blankFrames >= 3) latched = "";
            return;
        }
        blankFrames = 0;
        if (!hit.equals(candidate)) { candidate = hit; stableFrames = 0; }
        if (++stableFrames >= 2 && !hit.equals(latched)) {
            latched = hit;
            add(hit);
        }
    }

    private void manual() {
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

    private void add(String code) {
        for (Scan scan : scans) {
            if (!scan.code.equals(code)) continue;
            show(new AlertDialog.Builder(this).setTitle("Container already scanned")
                .setMessage(code + " is already in this batch. Add it if this is a separate document.")
                .setNegativeButton("Cancel", null).setPositiveButton("Add document", (d, w) -> save(code)).create());
            return;
        }
        save(code);
    }

    private void save(String code) {
        scans.add(new Scan(code, SAMPLE.equals(code) ? 0 : -1));
        persist();
        updateCounts();
        cameraMessage.setText("Saved · " + code);
        cameraMessage.performHapticFeedback(android.os.Build.VERSION.SDK_INT >= 30
            ? HapticFeedbackConstants.CONFIRM : HapticFeedbackConstants.VIRTUAL_KEY);
    }

    private void renderDocuments() {
        if (scans.isEmpty()) card().addView(text("Scanned documents will appear here.", 15, MUTED, false));
        for (int i = scans.size() - 1; i >= 0; i--) {
            Scan scan = scans.get(i);
            LinearLayout row = card();
            TextView number = text(scan.code, 19, INK, true);
            number.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            row.addView(number);
            row.addView(text(scan.sailing < 0 ? "Pending CPA match" :
                SAILINGS[scan.sailing][0] + "  ·  " + SAILINGS[scan.sailing][1] + "  ·  Demo", 14, MUTED, false));
            space(row, 7);
            row.addView(button("Review match", false, () -> review(scan)));
            space(content, 8);
        }
    }

    private void review(Scan scan) {
        String[] options = new String[SAILINGS.length + 1];
        options[0] = "Pending CPA match";
        for (int i = 0; i < SAILINGS.length; i++) options[i + 1] = android.text.TextUtils.join("  ·  ", SAILINGS[i]);
        int[] selected = {scan.sailing + 1};
        show(new AlertDialog.Builder(this).setTitle(scan.code)
            .setSingleChoiceItems(options, selected[0], (d, w) -> selected[0] = w)
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Remove", (d, w) -> content.post(() -> confirmRemove(scan)))
            .setPositiveButton("Save match", (d, w) -> {
                scan.sailing = selected[0] - 1;
                persist();
                render();
            }).create());
    }

    private void confirmRemove(Scan scan) {
        show(new AlertDialog.Builder(this).setTitle("Remove this document?")
            .setMessage(scan.code).setNegativeButton("Cancel", null)
            .setPositiveButton("Remove", (d, w) -> { scans.remove(scan); persist(); render(); }).create());
    }

    private int[] quantities() {
        int[] result = new int[SAILINGS.length];
        for (Scan scan : scans) if (scan.sailing >= 0) result[scan.sailing]++;
        return result;
    }

    private void renderMemo() {
        int[] counts = quantities();
        int total = 0;
        for (int count : counts) total += count;
        content.addView(text("Demo sailings · " + total + " matched · " + (scans.size() - total) + " pending", 14, PURPLE, true));
        space(content, 10);
        for (int i = 0; i < counts.length; i++) {
            if (counts[i] == 0) continue;
            LinearLayout row = card();
            String[] s = SAILINGS[i];
            row.addView(text(s[0], 21, INK, true));
            row.addView(text("VOY " + s[1] + "   ·   EXP. ROT. " + s[2], 14, MUTED, false));
            row.addView(text("ETD " + s[3] + "   ·   QTY " + counts[i] + "   ·   " + MLO, 14, PURPLE, true));
            space(content, 8);
        }
        if (total == 0) card().addView(text("Match a document to preview your memo.", 15, MUTED, false));
        else content.addView(button("Export demo CSV", true, () -> {
            exportText = csv();
            csvDestination.launch("BTB-export-memo-DEMO.csv");
        }));
    }

    private String csv() {
        StringBuilder result = new StringBuilder("\ufeffSL. No.,VSL NAME,VOY,EXP. ROT.,ETD,QTY,MLO\r\n");
        int[] counts = quantities();
        int serial = 0;
        for (int i = 0; i < counts.length; i++) {
            if (counts[i] == 0) continue;
            result.append(++serial).append(',').append(android.text.TextUtils.join(",", SAILINGS[i])).append(',')
                .append(counts[i]).append(',').append(MLO).append("\r\n");
        }
        return result.toString();
    }

    private void writeCsv(Uri target) {
        if (target == null) return;
        try (OutputStream output = getContentResolver().openOutputStream(target)) {
            if (output == null) throw new IOException("Destination unavailable");
            output.write((exportText == null ? csv() : exportText).getBytes(StandardCharsets.UTF_8));
            toast("Memo saved");
        } catch (IOException | SecurityException failure) { toast("Could not save the memo"); }
    }

    private void persist() {
        JSONArray data = new JSONArray();
        try {
            for (Scan scan : scans) data.put(new JSONObject().put("code", scan.code).put("sailing", scan.sailing));
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
                int sailing = row.optInt("sailing", -1);
                if (ContainerNumber.valid(code)) scans.add(new Scan(code, sailing >= 0 && sailing < SAILINGS.length ? sailing : -1));
            }
        } catch (JSONException invalid) { toast("Could not read the previous batch"); }
    }

    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); }

    private final class Reticle extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Reticle() { super(MainActivity.this); }
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
        }
    }
}
