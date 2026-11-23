package com.harborlens.app;

import android.Manifest;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.RectF;
import android.graphics.Path;
import android.widget.CheckBox;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
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
    private static final int INK = 0xff102e38, MUTED = 0xff536a71, BG = 0xffeef4f5;
    private static final int PURPLE = 0xff135669, TINT = 0xffdcecef;
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
    private String duplicate;
    private final Set<String> latched = new LinkedHashSet<>();
    private final java.util.HashMap<String, Integer> misses = new java.util.HashMap<>();
    private Set<String> candidate = java.util.Collections.emptySet();
    private final ArrayDeque<String> repeatQueue = new ArrayDeque<>();
    private LinkedHashMap<String, RectF> multi = new LinkedHashMap<>();
    private LinearLayout capturePanel;
    private TextView captureMessage;
    private WebView cpaWeb;
    private final java.util.concurrent.ExecutorService accountExecutor = java.util.concurrent.Executors.newSingleThreadExecutor();
    private Reticle reticle;
    private LinearLayout duplicatePanel;
    private TextView duplicateMessage;
    private final java.util.concurrent.ExecutorService exportExecutor = java.util.concurrent.Executors.newSingleThreadExecutor();
    private final java.util.HashMap<String, String[]> resolved = new java.util.HashMap<>();
    private final Map<String, androidx.work.WorkInfo> lookupWork = new java.util.HashMap<>();
    private final ArrayList<DocumentStatus> documentStatuses = new ArrayList<>();
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
        getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (cpaWeb != null) closeWeb(false);
                else if (duplicate != null) toast("Review the repeated document first");
                else if (tab != 0) { tab = 0; render(); }
                else { setEnabled(false); getOnBackPressedDispatcher().onBackPressed(); }
            }
        });
        results = getSharedPreferences(LookupWorker.STORE, MODE_PRIVATE);
        work = WorkManager.getInstance(this);
        load();
        render();
        work.getWorkInfosByTagLiveData(LookupWorker.TAG).observe(this, jobs -> {
            lookupWork.clear();
            for (var job : jobs) {
                if (job.getState().isFinished()) continue;
                for (String tag : job.getTags()) {
                    if (tag.startsWith(LookupWorker.CONTAINER_TAG)) {
                        lookupWork.put(tag.substring(LookupWorker.CONTAINER_TAG.length()), job);
                    }
                }
            }
            boolean changed = refreshMatches();
            if (tab == 1) for (DocumentStatus status : documentStatuses) status.update();
            if (tab == 2 && changed && !modal) render();
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
        accountExecutor.shutdown();
        if (cpaWeb != null) cpaWeb.destroy();
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
        documentStatuses.clear();
        stopCamera();
        root = column();
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            new int[] {0xfff8fcfc, BG, 0xffe1edf0}));
        root.setPadding(0, 0, 0, dp(10));
        setContentView(root);
        addAppBar();

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        LinearLayout.LayoutParams scrollPlacement = new LinearLayout.LayoutParams(-1, 0, 1);
        scrollPlacement.setMargins(dp(16), dp(12), dp(16), 0);
        root.addView(scroll, scrollPlacement);
        content = column();
        scroll.addView(content);
        if (tab == 0) renderScanner();
        else if (tab == 1) renderDocuments();
        else if (tab == 2) renderMemo();
        else renderSettings();

        space(root, 9);
        addFloatingNav();
    }

    private void addAppBar() {
        String[] titles = {"Scan documents", "Documents", "Shipping bill list", "Settings"};
        android.widget.Toolbar toolbar = new android.widget.Toolbar(this);
        toolbar.setContentInsetsRelative(dp(16), dp(16));
        toolbar.setTitle(titles[tab]);
        toolbar.setTitleTextColor(INK);
        toolbar.setBackgroundColor(0xfff8fcfc);
        root.addView(toolbar, new LinearLayout.LayoutParams(-1, dp(56)));
        View divider = new View(this);
        divider.setBackgroundColor(0xffdce8ea);
        root.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
    }

    private void addFloatingNav() {
        LinearLayout nav = new LinearLayout(this);
        nav.setPadding(dp(5), dp(4), dp(5), dp(4));
        GradientDrawable glass = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
            new int[]{0xfaffffff, 0xe5f0f8f9, 0xf5ffffff});
        glass.setCornerRadius(dp(30));
        glass.setStroke(dp(1), 0xffffffff);
        nav.setBackground(glass);
        nav.setElevation(dp(8));
        LinearLayout.LayoutParams placement = new LinearLayout.LayoutParams(-1, dp(65));
        placement.setMargins(dp(20), 0, dp(20), dp(3));
        root.addView(nav, placement);
        String[] labels = {"Scan", "Docs", "Reports", "Settings"};
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            LinearLayout item = column();
            item.setGravity(Gravity.CENTER);
            item.setSelected(i == tab);
            item.setContentDescription(labels[i] + (i == tab ? ", selected" : ""));
            item.setFocusable(true);
            item.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x24135669),
                shape(i == tab ? TINT : Color.TRANSPARENT, 24), shape(Color.WHITE, 24)));
            item.addView(new NavGlyph(i, i == tab ? PURPLE : MUTED), new LinearLayout.LayoutParams(dp(23), dp(23)));
            TextView label = text(labels[i], 11, i == tab ? PURPLE : MUTED, i == tab);
            label.setGravity(Gravity.CENTER);
            label.setMaxLines(1);
            label.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams labelPlacement = new LinearLayout.LayoutParams(-1, -2);
            labelPlacement.topMargin = dp(3);
            item.addView(label, labelPlacement);
            item.setOnClickListener(view -> {
                if (duplicate != null) { toast("Review the repeated document first"); return; }
                if (index != tab) { tab = index; render(); }
            });
            nav.addView(item, new LinearLayout.LayoutParams(0, -1, 1));
        }
    }

    private final class NavGlyph extends View {
        private final int icon, tint;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        NavGlyph(int icon, int tint) { super(MainActivity.this); this.icon = icon; this.tint = tint; }
        @Override protected void onDraw(Canvas c) {
            c.save();
            c.scale(getWidth()/24f, getHeight()/24f);
            paint.setColor(tint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeWidth(1.8f);
            if (icon == 0) {
                Path path = new Path();
                path.moveTo(3,8); path.lineTo(3,3); path.lineTo(8,3);
                path.moveTo(16,3); path.lineTo(21,3); path.lineTo(21,8);
                path.moveTo(21,16); path.lineTo(21,21); path.lineTo(16,21);
                path.moveTo(8,21); path.lineTo(3,21); path.lineTo(3,16);
                c.drawPath(path, paint);
                c.drawLine(5,12,19,12,paint);
            } else if (icon == 1) {
                c.drawRoundRect(5,3,19,21,3,3,paint);
                c.drawLine(8,9,16,9,paint); c.drawLine(8,13,16,13,paint); c.drawLine(8,17,13,17,paint);
            } else if (icon == 2) {
                c.drawRoundRect(3,4,21,20,3,3,paint);
                c.drawLine(3,9,21,9,paint); c.drawLine(10,9,10,20,paint); c.drawLine(3,14,21,14,paint);
            } else {
                c.drawLine(3,6,21,6,paint); c.drawLine(3,12,21,12,paint); c.drawLine(3,18,21,18,paint);
                paint.setStyle(Paint.Style.FILL);
                c.drawCircle(8,6,2.7f,paint); c.drawCircle(16,12,2.7f,paint); c.drawCircle(10,18,2.7f,paint);
            }
            c.restore();
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
        capturePanel = column();
        capturePanel.setPadding(dp(12), dp(9), dp(12), dp(9));
        capturePanel.setBackground(shape(TINT, 18));
        captureMessage = text("", 14, PURPLE, true);
        capturePanel.addView(captureMessage);
        LinearLayout captureChoices = new LinearLayout(this);
        capturePanel.addView(captureChoices);
        captureChoices.addView(button("Capture one", false, () -> capture(false)), new LinearLayout.LayoutParams(0, -2, 1));
        captureChoices.addView(button("Capture all", true, () -> capture(true)), new LinearLayout.LayoutParams(0, -2, 1));
        capturePanel.setVisibility(View.GONE);
        content.addView(capturePanel);
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
            if (latched.size() == 1) add(latched.iterator().next());
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
        candidate = java.util.Collections.emptySet();
        latched.clear();
        multi.clear();
        misses.clear();
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
        if (token != generation || image == null || modal || !hasWindowFocus()) {
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
                    if (token == generation && !modal && hasWindowFocus()) recognize(result, source);
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
        LinkedHashMap<String, RectF> hits = new LinkedHashMap<>();
        RectF visible = new RectF(0, 0, preview.getWidth(), preview.getHeight());
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
                    if (RectF.intersects(bounds, visible)) hits.putIfAbsent(code, bounds);
                }
            }
        }

        reticle.mark(hits);
        if (duplicate != null) return;
        if (hits.isEmpty()) {
            capturePanel.setVisibility(View.GONE);
            stableFrames = 0;
            if (++blankFrames >= 3) {
                latched.clear();
                misses.clear();
                multi.clear();
                capturePanel.setVisibility(View.GONE);
                cameraMessage.setText("Aim at a container number");
                cameraMessage.setBackground(shape(0xe0102e38, 19));
            }
            return;
        }
        blankFrames = 0;
        latched.removeIf(code -> {
            if (hits.containsKey(code)) { misses.remove(code); return false; }
            if (misses.merge(code, 1, Integer::sum) < 3) return false;
            misses.remove(code);
            return true;
        });
        if (!hits.keySet().equals(candidate)) {
            candidate = new LinkedHashSet<>(hits.keySet());
            stableFrames = 0;
        }
        if (stableFrames < 3) stableFrames++;
        if (stableFrames < 2) {
            capturePanel.setVisibility(View.GONE);
            return;
        }
        hits.keySet().removeAll(latched);
        if (hits.isEmpty()) {
            multi.clear();
            capturePanel.setVisibility(View.GONE);
            return;
        }
        if (hits.size() > 1 || !multi.isEmpty()) {
            if (multi.isEmpty()) cameraMessage.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
            multi = hits;
            captureMessage.setText(hits.size() + " ready · Capture one counts the number nearest the center.");
            capturePanel.setVisibility(View.VISIBLE);
            cameraMessage.setText("Choose which containers to count");
            cameraMessage.setBackground(shape(0xe0102e38, 19));
        } else if (stableFrames >= 3) {
            String code = hits.keySet().iterator().next();
            latched.add(code);
            add(code);
        }
    }

    private void capture(boolean all) {
        if (multi.isEmpty() || duplicate != null || capturePanel.getVisibility() != View.VISIBLE) return;
        ArrayList<String> chosen = new ArrayList<>(multi.keySet());
        chosen.sort(java.util.Comparator.comparingDouble(code -> {
            RectF rect = multi.get(code);
            return Math.hypot(rect.centerX() - preview.getWidth()/2f, rect.centerY() - preview.getHeight()/2f);
        }));
        latched.addAll(multi.keySet());
        multi.clear();
        capturePanel.setVisibility(View.GONE);
        if (!all) { add(chosen.get(0)); return; }
        for (String code : chosen) {
            if (isRepeat(code)) repeatQueue.add(code);
            else save(code);
        }
        if (!repeatQueue.isEmpty()) add(repeatQueue.removeFirst());
        else cameraMessage.setText(chosen.size() + " documents counted");
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
            reticle.mark(java.util.Collections.emptyMap());
        }
        if (!repeatQueue.isEmpty()) add(repeatQueue.removeFirst());
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

    private void queue(String code) { queue(code, ExistingWorkPolicy.KEEP); }

    private void queue(String code, ExistingWorkPolicy policy) {
        var request = new OneTimeWorkRequest.Builder(LookupWorker.class)
            .setInputData(new Data.Builder().putString("container", code).build())
            .setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .addTag(LookupWorker.TAG).addTag(LookupWorker.CONTAINER_TAG + code).build();
        work.enqueueUniqueWork("cpa-" + code, policy, request);
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
        Set<String> pending = new LinkedHashSet<>();
        for (Scan scan : scans) if (!resolved.containsKey(scan.code)) pending.add(scan.code);
        for (String code : pending) {
            var active = lookupWork.get(code);
            if (active != null && active.getState() == androidx.work.WorkInfo.State.RUNNING) continue;
            queue(code, ExistingWorkPolicy.REPLACE);
        }
        render();
    }

    private void renderDocuments() {
        LinearLayout actions = new LinearLayout(this);
        content.addView(actions);
        Button retry = button("Retry CPA", false, this::retryPending);
        retry.setEnabled(!scans.isEmpty());
        actions.addView(retry, new LinearLayout.LayoutParams(0, -2, 1));
        Button clear = button("Remove all", false, this::confirmRemoveAll);
        clear.setTextColor(RED);
        clear.setEnabled(!scans.isEmpty());
        LinearLayout.LayoutParams clearPlacement = new LinearLayout.LayoutParams(0, -2, 1);
        clearPlacement.leftMargin = dp(8);
        actions.addView(clear, clearPlacement);
        space(content, 8);
        if (scans.isEmpty()) {
            card().addView(text("Scanned documents will appear here.", 15, MUTED, false));
            return;
        }
        content.addView(text(scans.size() + (scans.size() == 1 ? " document" : " documents") + " · Swipe for more columns",
            12, MUTED, false));
        space(content, 6);
        android.widget.HorizontalScrollView horizontal = new android.widget.HorizontalScrollView(this);
        horizontal.setBackground(shape(Color.WHITE, 12));
        horizontal.setClipToOutline(true);
        content.addView(horizontal, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout grid = column();
        grid.setShowDividers(LinearLayout.SHOW_DIVIDER_MIDDLE);
        grid.setDividerDrawable(gridDivider());
        horizontal.addView(grid, new FrameLayout.LayoutParams(-2, -2));

        String[] headings = {"SL", "Container no.", "CPA status / response", "Vessel",
            "Voyage", "Rotation", "ETD", "MLO", "Action"};
        int[] widths = {38, 124, 168, 132, 72, 96, 99, 62, 100};
        LinearLayout header = gridRow(grid, 0xffdcecef);
        for (int i = 0; i < headings.length; i++) {
            TextView label = text(headings[i], 12, INK, true);
            gridCell(header, label, widths[i], i == 0);
        }
        for (int i = 0; i < scans.size(); i++) {
            Scan scan = scans.get(i);
            LinearLayout row = gridRow(grid, i % 2 == 0 ? Color.WHITE : 0xfff6fafb);
            gridCell(row, text(Integer.toString(i + 1), 12, MUTED, false), widths[0], true);
            TextView number = text(scan.code, 13, INK, true);
            number.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            gridCell(row, number, widths[1], false);
            LinearLayout statusCell = column();
            gridCell(row, statusCell, widths[2], false);
            TextView[] values = new TextView[5];
            for (int j = 0; j < values.length; j++) {
                values[j] = text("—", 13, INK, false);
                gridCell(row, values[j], widths[j + 3], false);
            }
            DocumentStatus status = new DocumentStatus(scan.code, statusCell, values);
            documentStatuses.add(status);
            status.update();
            Button remove = button("Remove", false, () -> confirmRemove(scan));
            remove.setTextColor(RED);
            remove.setTextSize(12);
            remove.setContentDescription("Remove document " + (i + 1) + ", " + scan.code);
            gridCell(row, remove, widths[8], true);
        }
    }

    private GradientDrawable gridDivider() {
        GradientDrawable line = new GradientDrawable();
        line.setColor(0xffdce8ea);
        line.setSize(dp(1), dp(1));
        return line;
    }

    private LinearLayout gridRow(LinearLayout grid, int color) {
        LinearLayout row = new LinearLayout(this);
        row.setBackgroundColor(color);
        row.setGravity(Gravity.TOP);
        row.setShowDividers(LinearLayout.SHOW_DIVIDER_MIDDLE);
        row.setDividerDrawable(gridDivider());
        grid.addView(row, new LinearLayout.LayoutParams(-2, -2));
        return row;
    }

    private void gridCell(LinearLayout row, View cell, int width, boolean centered) {
        if (!(cell instanceof Button)) cell.setPadding(dp(8), dp(10), dp(8), dp(10));
        if (centered && cell instanceof TextView) ((TextView) cell).setGravity(Gravity.CENTER);
        row.addView(cell, new LinearLayout.LayoutParams(dp(width), -2));
    }

    private final class DocumentStatus {
        private final String code;
        private final TextView title, detail;
        private final TextView[] values;
        private final android.widget.ProgressBar bar;

        DocumentStatus(String code, LinearLayout row, TextView[] values) {
            this.code = code;
            this.values = values;
            title = text("", 14, INK, true);
            title.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            detail = text("", 12, MUTED, false);
            bar = new android.widget.ProgressBar(MainActivity.this, null, android.R.attr.progressBarStyleHorizontal);
            bar.setMax(3);
            bar.setContentDescription("CPA lookup progress");
            var placement = new LinearLayout.LayoutParams(-1, dp(4));
            placement.topMargin = dp(5);
            row.addView(title);
            row.addView(bar, placement);
            row.addView(detail);
        }

        void update() {
            String[] sailing = resolved.get(code);
            var job = lookupWork.get(code);
            String failure = results.getString(code + "-error", "");
            boolean running = job != null && job.getState() == androidx.work.WorkInfo.State.RUNNING;
            bar.setVisibility(running && sailing == null ? View.VISIBLE : View.GONE);
            if (sailing != null) {
                title.setText("CPA matched");
                detail.setText("Vessel and departure confirmed.");
                int[] fields = {1, 2, 3, 4, 6};
                for (int i = 0; i < values.length; i++) values[i].setText(sailing[fields[i]]);
            } else if (running) {
                int step = job.getProgress().getInt("step", 0);
                String[] stages = {"Starting CPA lookup", "1 of 3 · Container location",
                    "2 of 3 · Vessel and voyage", "3 of 3 · Departure date"};
                title.setText(stages[Math.max(0, Math.min(step, 3))]);
                bar.setIndeterminate(step == 0);
                if (step > 0) bar.setProgress(step);
                detail.setText(job.getProgress().getString("detail"));
            } else if (job != null) {
                title.setText("CPA lookup queued");
                detail.setText(job.getRunAttemptCount() > 0
                    ? (failure.isEmpty() ? "Waiting for the next attempt." : failure + "\nRetry scheduled.")
                    : "Starts when a network connection is available.");
            } else if (!failure.isEmpty()) {
                title.setText("CPA lookup needs attention");
                detail.setText(failure);
            } else {
                title.setText("Waiting for CPA");
                detail.setText("Lookup has not started yet.");
            }
        }
    }

    private void confirmRemoveAll() {
        int count = scans.size();
        if (count == 0) return;
        show(new AlertDialog.Builder(this).setTitle("Remove all scanned documents?")
            .setMessage("This clears " + count + " saved scans and their counts from the current batch.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Remove all", (d, w) -> {
                if (!storeScans(java.util.Collections.emptyList())) return;
                scans.clear();
                work.cancelAllWorkByTag(LookupWorker.TAG);
                render();
                saveReports(null, false);
            }).create());
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
        boolean configured = CpaAccount.configured(this);
        account.addView(text(configured ? "Account saved on this device" : "Set up automatic sign-in or use the CPA website.", 14, MUTED, false));
        space(account, 10);
        EditText user = new EditText(this);
        user.setHint("CPA username");
        user.setSingleLine();
        user.setAutofillHints(View.AUTOFILL_HINT_USERNAME);
        account.addView(user);
        EditText password = new EditText(this);
        password.setHint("CPA password");
        password.setSingleLine();
        password.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        password.setAutofillHints(View.AUTOFILL_HINT_PASSWORD);
        account.addView(password);
        CheckBox encoded = new CheckBox(this);
        encoded.setText("Use CPA’s encoded password field");
        account.addView(encoded);
        account.addView(text("Native login defaults to MD5. If CPA rejects it, use web sign-in or enter the exact encoded field.", 12, MUTED, false));
        space(account, 9);
        Button save = button("Save account & sign in", true, () -> {});
        save.setOnClickListener(view -> {
            String name = user.getText().toString().trim();
            String secret = password.getText().toString();
            boolean alreadyEncoded = encoded.isChecked();
            if (name.isEmpty()) { user.setError("Enter your CPA username"); return; }
            if (secret.isEmpty()) { password.setError("Enter your CPA password"); return; }
            save.setEnabled(false);
            save.setText("Signing in…");
            accountExecutor.execute(() -> {
                String message;
                boolean signedIn = false;
                try {
                    CpaAccount.save(this, name, secret, alreadyEncoded);
                    CpaAccount.signIn(this, CookieManager.getInstance().getCookie(Cpa.ROOT));
                    signedIn = true;
                    message = "CPA session saved · retrying pending lookups";
                } catch (Cpa.Auth failure) {
                    message = failure.getMessage();
                } catch (Exception failure) {
                    message = "Could not complete CPA sign-in. Try again or use web sign-in.";
                }
                String status = message;
                boolean retry = signedIn;
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    password.setText("");
                    save.setEnabled(true);
                    save.setText("Save account & sign in");
                    toast(status);
                    if (retry && cpaWeb == null) retryPending();
                });
            });
        });
        account.addView(save);
        space(account, 7);
        account.addView(button("Open full-screen CPA sign-in", false, this::signIn));
        space(account, 7);
        account.addView(button("Clear saved account & session", false, () -> {
            accountExecutor.execute(() -> {
                CpaAccount.clear(this);
                runOnUiThread(() -> CookieManager.getInstance().removeAllCookies(done -> {
                    CookieManager.getInstance().flush();
                    if (isFinishing() || isDestroyed()) return;
                    toast("CPA account and session cleared");
                    if (cpaWeb == null) render();
                }));
            });
        }));
        space(content, 10);
        LinearLayout about = card();
        about.addView(text("About BSCA SB Scan", 20, INK, true));
        about.addView(text("© 2026 BS Cargo Agency Ltd.\nAuthor · tas33n\nVersion " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ") · 23 November 2026", 14, MUTED, false));
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
        if (cpaWeb != null) return;
        stopCamera();
        LinearLayout screen = column();
        screen.setBackgroundColor(BG);
        screen.setPadding(dp(12), dp(8), dp(12), dp(8));
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        screen.addView(bar);
        bar.addView(button("Back", false, () -> closeWeb(false)));
        TextView title = text("CPA sign-in", 18, INK, true);
        title.setGravity(Gravity.CENTER);
        bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        bar.addView(button("Save session", true, () -> closeWeb(true)));
        WebView web = new WebView(this);
        cpaWeb = web;
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
        screen.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(screen);
        web.loadUrl(Cpa.LOGIN);
    }

    private void closeWeb(boolean save) {
        if (cpaWeb == null) return;
        if (save) {
            String cookies = CookieManager.getInstance().getCookie(Cpa.ROOT);
            if (cookies == null || !cookies.contains("ci_session=")) {
                toast("Sign in on CPA before saving");
                return;
            }
            CookieManager.getInstance().flush();
        }
        WebView web = cpaWeb;
        cpaWeb = null;
        if (save) retryPending();
        else render();
        web.destroy();
    }

    private void persist() { storeScans(scans); }

    private boolean storeScans(java.util.List<Scan> documents) {
        JSONArray data = new JSONArray();
        try {
            for (Scan scan : documents) data.put(new JSONObject().put("code", scan.code));
            if (getPreferences(MODE_PRIVATE).edit().putString("scans", data.toString()).commit()) return true;
        } catch (JSONException invalid) {
            // Keep the current batch if serialization fails.
        }
        toast("Could not save the batch to this device");
        return false;
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
        private final LinkedHashMap<String, RectF> highlights = new LinkedHashMap<>();
        Reticle() { super(MainActivity.this); }
        void mark(Map<String, RectF> bounds) {
            highlights.keySet().retainAll(bounds.keySet());
            for (var entry : bounds.entrySet()) {
                RectF next = entry.getValue();
                RectF shown = highlights.get(entry.getKey());
                if (shown == null || !RectF.intersects(shown, next)) {
                    highlights.put(entry.getKey(), new RectF(next));
                } else {
                    shown.set(shown.left + (next.left - shown.left) * .8f, shown.top + (next.top - shown.top) * .8f,
                        shown.right + (next.right - shown.right) * .8f, shown.bottom + (next.bottom - shown.bottom) * .8f);
                }
            }
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
            paint.setColor(0xffe2f5f4);
            paint.setStrokeWidth(dp(3));
            float edge = dp(17);
            for (float x : new float[]{left, right}) {
                for (float y : new float[]{top, bottom}) {
                    canvas.drawLine(x, y, x + (x == left ? edge : -edge), y, paint);
                    canvas.drawLine(x, y, x, y + (y == top ? edge : -edge), paint);
                }
            }
            for (var hit : highlights.entrySet()) {
                int color = hit.getKey().equals(duplicate) || (!latched.contains(hit.getKey()) && isRepeat(hit.getKey()))
                    ? RED : GREEN;
                RectF outline = new RectF(hit.getValue());
                outline.inset(-dp(5), -dp(4));
                paint.setColor((color & 0x00ffffff) | 0x44000000);
                canvas.drawRoundRect(outline, dp(7), dp(7), paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setColor(color);
                paint.setStrokeWidth(1.25f * getResources().getDisplayMetrics().density);
                canvas.drawRoundRect(outline, dp(7), dp(7), paint);
                paint.setStyle(Paint.Style.FILL);
            }
        }
    }
}
