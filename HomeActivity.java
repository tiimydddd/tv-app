package com.example.cozytv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cozy TV launcher: a living-room scene where each object is an app.
 *
 * Optional files (put them in /sdcard/Android/data/com.example.cozytv/files/):
 *   scene.jpg / scene.png          background picture (16:9)
 *   config.json                    custom hotspots (see README)
 *   clips/<package name>.mp4       animation played before the app opens
 */
public class HomeActivity extends Activity {

    static class Spot {
        final String pkg, name, hint;
        final float x, y, w, h; // centre + size, as fractions of the screen

        Spot(String pkg, String name, String hint, float x, float y, float w, float h) {
            this.pkg = pkg;
            this.name = name;
            this.hint = hint;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }

        boolean isAll() {
            return "ALL".equals(pkg);
        }
    }

    /** Places every hotspot view by fractional coordinates. */
    static class Stage extends FrameLayout {
        Stage(Context c) {
            super(c);
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            super.onLayout(changed, l, t, r, b);
            int W = r - l, H = b - t;
            for (int i = 0; i < getChildCount(); i++) {
                View v = getChildAt(i);
                Object tag = v.getTag();
                if (tag instanceof Spot) {
                    Spot s = (Spot) tag;
                    int w = Math.round(s.w * W), h = Math.round(s.h * H);
                    int cx = Math.round(s.x * W), cy = Math.round(s.y * H);
                    v.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                            MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
                    v.layout(cx - w / 2, cy - h / 2, cx - w / 2 + w, cy - h / 2 + h);
                }
            }
        }
    }

    private File dataDir;
    private Stage stage;
    private ImageView bg;
    private VideoView video;
    private LinearLayout labelBox;
    private TextView labelName, labelHint;
    private TextClock clock;
    private final List<View> spotViews = new ArrayList<>();
    private List<Spot> spots;
    private Spot playing = null;
    private boolean busy = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        dataDir = getExternalFilesDir(null);
        if (dataDir == null) dataDir = getFilesDir();
        new File(dataDir, "clips").mkdirs();

        spots = loadSpots();

        stage = new Stage(this);
        stage.setBackgroundColor(Color.BLACK);

        bg = new ImageView(this);
        bg.setScaleType(ImageView.ScaleType.FIT_XY);
        bg.setImageBitmap(loadScene());
        stage.addView(bg, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        for (Spot s : spots) {
            View v = makeSpotView(s);
            spotViews.add(v);
            stage.addView(v, new FrameLayout.LayoutParams(1, 1));
        }

        // Clock (top-left)
        clock = new TextClock(this);
        clock.setFormat12Hour("HH:mm");
        clock.setFormat24Hour("HH:mm");
        clock.setTextSize(44);
        clock.setTextColor(0xFFFFFFFF);
        clock.setShadowLayer(8, 0, 2, 0xAA000000);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START);
        clp.setMargins(dp(48), dp(32), 0, 0);
        stage.addView(clock, clp);

        // Label (bottom-left)
        labelBox = new LinearLayout(this);
        labelBox.setOrientation(LinearLayout.VERTICAL);
        labelName = new TextView(this);
        labelName.setTextSize(36);
        labelName.setTextColor(0xFFFFFFFF);
        labelName.setShadowLayer(10, 0, 2, 0xCC000000);
        labelHint = new TextView(this);
        labelHint.setTextSize(20);
        labelHint.setTextColor(0xFFFFE9C8);
        labelHint.setShadowLayer(8, 0, 2, 0xCC000000);
        labelBox.addView(labelName);
        labelBox.addView(labelHint);
        FrameLayout.LayoutParams llp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.START);
        llp.setMargins(dp(48), 0, 0, dp(40));
        stage.addView(labelBox, llp);

        // Video overlay for the "pick up the object" animations
        video = new VideoView(this);
        video.setVisibility(View.GONE);
        stage.addView(video, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(stage);

        if (!spotViews.isEmpty()) spotViews.get(0).requestFocus();
        if (!spots.isEmpty()) showLabel(spots.get(0));
    }

    @Override
    protected void onResume() {
        super.onResume();
        resetUi();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        resetUi();
    }

    @Override
    public void onBackPressed() {
        // A home screen should not exit on Back.
        if (playing != null) cancelClip();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (playing != null) {
            if (keyCode == KeyEvent.KEYCODE_BACK) cancelClip();
            else finishClip(playing);
            return true;
        }
        if (busy) return true;
        return super.onKeyDown(keyCode, event);
    }

    // ---------- UI helpers ----------

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private View makeSpotView(final Spot s) {
        View v = new View(this);
        v.setTag(s);

        GradientDrawable focused = new GradientDrawable();
        focused.setCornerRadius(dp(18));
        focused.setColor(0x22FFFFFF);
        focused.setStroke(dp(4), 0xFFFFE9A8);
        GradientDrawable normal = new GradientDrawable();
        normal.setColor(Color.TRANSPARENT);
        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_focused}, focused);
        sl.addState(new int[]{}, normal);
        v.setBackground(sl);

        v.setFocusable(true);
        v.setClickable(true);
        v.setOnFocusChangeListener((view, hasFocus) -> {
            view.animate().scaleX(hasFocus ? 1.08f : 1f).scaleY(hasFocus ? 1.08f : 1f)
                    .setDuration(150).start();
            if (hasFocus) showLabel(s);
        });
        v.setOnClickListener(view -> launch(s));
        return v;
    }

    private void showLabel(Spot s) {
        labelName.setText(s.name);
        labelHint.setText(s.hint);
    }

    private void hideUi() {
        for (View v : spotViews) v.animate().alpha(0f).setDuration(200).start();
        labelBox.animate().alpha(0f).setDuration(200).start();
        clock.animate().alpha(0f).setDuration(200).start();
    }

    private void resetUi() {
        busy = false;
        playing = null;
        video.stopPlayback();
        video.setVisibility(View.GONE);
        bg.animate().cancel();
        bg.setScaleX(1f);
        bg.setScaleY(1f);
        for (View v : spotViews) {
            v.animate().cancel();
            v.setAlpha(1f);
        }
        labelBox.animate().cancel();
        labelBox.setAlpha(1f);
        clock.animate().cancel();
        clock.setAlpha(1f);
    }

    // ---------- Launching ----------

    private void launch(Spot s) {
        if (busy || playing != null) return;
        if (s.isAll()) {
            showAllApps();
            return;
        }
        busy = true;
        File clip = new File(dataDir, "clips/" + s.pkg + ".mp4");
        if (clip.exists()) playClip(s, clip);
        else zoomThenLaunch(s);
    }

    private void playClip(final Spot s, File clip) {
        hideUi();
        playing = s;
        video.setVisibility(View.VISIBLE);
        video.setOnCompletionListener(mp -> finishClip(s));
        video.setOnErrorListener((mp, what, extra) -> {
            finishClip(s);
            return true;
        });
        video.setVideoPath(clip.getAbsolutePath());
        video.start();
    }

    private void finishClip(Spot s) {
        if (playing == null) return;
        playing = null;
        launchApp(s);
    }

    private void cancelClip() {
        resetUi();
    }

    private void zoomThenLaunch(final Spot s) {
        hideUi();
        bg.setPivotX(s.x * stage.getWidth());
        bg.setPivotY(s.y * stage.getHeight());
        bg.animate().scaleX(1.9f).scaleY(1.9f).setDuration(600)
                .withEndAction(() -> launchApp(s)).start();
    }

    private void launchApp(Spot s) {
        if (!launchPackage(s.pkg)) {
            Toast.makeText(this, s.name + " 没有安装", Toast.LENGTH_LONG).show();
            resetUi();
        }
    }

    private boolean launchPackage(String pkg) {
        PackageManager pm = getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        if (i == null) return false;
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void showAllApps() {
        PackageManager pm = getPackageManager();
        Map<String, ResolveInfo> map = new LinkedHashMap<>();
        String[] cats = {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER};
        for (String c : cats) {
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(c);
            for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
                String p = ri.activityInfo.packageName;
                if (!p.equals(getPackageName()) && !map.containsKey(p)) map.put(p, ri);
            }
        }
        final List<String[]> items = new ArrayList<>();
        for (Map.Entry<String, ResolveInfo> e : map.entrySet()) {
            items.add(new String[]{String.valueOf(e.getValue().loadLabel(pm)), e.getKey()});
        }
        Collections.sort(items, new Comparator<String[]>() {
            @Override
            public int compare(String[] a, String[] b) {
                return a[0].compareToIgnoreCase(b[0]);
            }
        });
        CharSequence[] labels = new CharSequence[items.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = items.get(i)[0];

        AlertDialog.Builder b = new AlertDialog.Builder(this,
                android.R.style.Theme_Material_Dialog_Alert);
        b.setTitle("所有应用");
        b.setItems(labels, (d, which) -> launchPackage(items.get(which)[1]));
        b.show();
    }

    // ---------- Config & scene ----------

    private List<Spot> loadSpots() {
        File f = new File(dataDir, "config.json");
        if (f.exists()) {
            try {
                JSONObject o = new JSONObject(readText(f));
                JSONArray a = o.getJSONArray("spots");
                List<Spot> list = new ArrayList<>();
                for (int i = 0; i < a.length(); i++) {
                    JSONObject j = a.getJSONObject(i);
                    list.add(new Spot(
                            j.getString("pkg"),
                            j.optString("name", j.getString("pkg")),
                            j.optString("hint", "按确认键打开"),
                            (float) j.getDouble("x"), (float) j.getDouble("y"),
                            (float) j.optDouble("w", 0.1), (float) j.optDouble("h", 0.15)));
                }
                if (!list.isEmpty()) return list;
            } catch (Throwable ignored) {
                // fall back to defaults
            }
        }
        List<Spot> d = new ArrayList<>();
        d.add(new Spot("com.netflix.ninja", "Netflix", "抓一把爆米花", 0.52f, 0.70f, 0.09f, 0.18f));
        d.add(new Spot("com.google.android.youtube.tv", "YouTube", "把壁炉上的画框拿下来", 0.56f, 0.17f, 0.10f, 0.16f));
        d.add(new Spot("com.disney.disneyplus", "Disney+", "抱起沙发上的抱枕", 0.14f, 0.52f, 0.12f, 0.20f));
        d.add(new Spot("ALL", "所有应用", "翻翻书架上的应用", 0.865f, 0.49f, 0.13f, 0.54f));
        return d;
    }

    private String readText(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return bo.toString("UTF-8");
    }

    private Bitmap loadScene() {
        String[] names = {"scene.jpg", "scene.png", "scene.webp"};
        for (String n : names) {
            File f = new File(dataDir, n);
            if (!f.exists()) continue;
            try {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(f.getPath(), o);
                int sample = 1;
                while (o.outWidth / sample > 2000) sample *= 2;
                BitmapFactory.Options o2 = new BitmapFactory.Options();
                o2.inSampleSize = sample;
                Bitmap bm = BitmapFactory.decodeFile(f.getPath(), o2);
                if (bm != null) return bm;
            } catch (Throwable ignored) {
                // try next / placeholder
            }
        }
        return drawPlaceholder(1280, 720);
    }

    /** Simple painted living room, used until you supply your own scene.jpg. */
    private static Bitmap drawPlaceholder(int W, int H) {
        Bitmap bm = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bm);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        // wall + floor
        p.setShader(new LinearGradient(0, 0, 0, H, 0xFF2B1E2E, 0xFF5A3524, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, W, H, p);
        p.setShader(null);
        p.setColor(0xFF3E2415);
        c.drawRect(0, H * 0.74f, W, H, p);

        // fireplace
        p.setColor(0xFF7A6A5A);
        c.drawRect(W * 0.43f, H * 0.27f, W * 0.70f, H * 0.74f, p);
        p.setColor(0xFF5B3A22);
        c.drawRect(W * 0.40f, H * 0.23f, W * 0.73f, H * 0.27f, p);
        p.setColor(0xFF1E0D06);
        c.drawRect(W * 0.48f, H * 0.40f, W * 0.65f, H * 0.74f, p);
        p.setShader(new RadialGradient(W * 0.565f, H * 0.62f, W * 0.22f,
                0x88FF9A33, 0x00FF9A33, Shader.TileMode.CLAMP));
        c.drawCircle(W * 0.565f, H * 0.62f, W * 0.22f, p);
        p.setShader(null);
        p.setColor(0xFFFF8A1F);
        c.drawOval(new RectF(W * 0.52f, H * 0.50f, W * 0.61f, H * 0.73f), p);
        p.setColor(0xFFFFD36B);
        c.drawOval(new RectF(W * 0.54f, H * 0.58f, W * 0.59f, H * 0.73f), p);

        // picture frame with play button on the mantel (YouTube)
        RectF fr = new RectF(W * 0.525f, H * 0.11f, W * 0.595f, H * 0.23f);
        p.setColor(0xFFDDDDDD);
        c.drawRoundRect(fr, 6, 6, p);
        p.setColor(0xFFD01818);
        c.drawRect(fr.left + 8, fr.top + 8, fr.right - 8, fr.bottom - 8, p);
        p.setColor(Color.WHITE);
        Path tri = new Path();
        float tx = fr.centerX(), ty = fr.centerY(), ts = H * 0.025f;
        tri.moveTo(tx - ts * 0.7f, ty - ts);
        tri.lineTo(tx - ts * 0.7f, ty + ts);
        tri.lineTo(tx + ts * 1.1f, ty);
        tri.close();
        c.drawPath(tri, p);

        // sofa + fluffy blue cushion (Disney+)
        p.setColor(0xFFA692B8);
        c.drawRoundRect(new RectF(W * 0.04f, H * 0.38f, W * 0.36f, H * 0.58f), 30, 30, p);
        p.setColor(0xFFB8A5C8);
        c.drawRoundRect(new RectF(W * 0.03f, H * 0.52f, W * 0.38f, H * 0.76f), 30, 30, p);
        p.setColor(0xFF4D9BFF);
        c.drawCircle(W * 0.14f, H * 0.52f, W * 0.05f, p);
        p.setColor(Color.WHITE);
        c.drawCircle(W * 0.125f, H * 0.505f, W * 0.009f, p);
        c.drawCircle(W * 0.155f, H * 0.505f, W * 0.009f, p);
        p.setColor(0xFF222222);
        c.drawCircle(W * 0.127f, H * 0.507f, W * 0.004f, p);
        c.drawCircle(W * 0.157f, H * 0.507f, W * 0.004f, p);

        // coffee table, remote
        p.setColor(0xFF8A5530);
        c.drawRect(W * 0.40f, H * 0.76f, W * 0.72f, H * 0.80f, p);
        c.drawRect(W * 0.42f, H * 0.80f, W * 0.44f, H * 0.90f, p);
        c.drawRect(W * 0.68f, H * 0.80f, W * 0.70f, H * 0.90f, p);
        p.setColor(0xFF222222);
        c.drawRect(W * 0.60f, H * 0.745f, W * 0.65f, H * 0.76f, p);

        // popcorn bucket (Netflix)
        float bx = W * 0.475f, bw = W * 0.09f, by = H * 0.64f, bh = H * 0.12f;
        int stripes = 6;
        for (int i = 0; i < stripes; i++) {
            p.setColor(i % 2 == 0 ? 0xFFD62828 : 0xFFFFFFFF);
            c.drawRect(bx + bw * i / stripes, by, bx + bw * (i + 1) / stripes, by + bh, p);
        }
        p.setColor(0xFFFFF1C9);
        for (int i = 0; i < 7; i++) {
            c.drawCircle(bx + bw * (i + 0.5f) / 7f, by - H * 0.008f + (i % 2) * H * 0.01f, W * 0.011f, p);
        }

        // bookshelf (all apps)
        p.setColor(0xFF5B3A22);
        c.drawRect(W * 0.80f, H * 0.22f, W * 0.93f, H * 0.76f, p);
        p.setColor(0xFF2A190D);
        c.drawRect(W * 0.81f, H * 0.23f, W * 0.92f, H * 0.75f, p);
        int[] bookColors = {0xFFC0392B, 0xFF2980B9, 0xFF27AE60, 0xFFF39C12, 0xFF8E44AD};
        for (int row = 0; row < 4; row++) {
            float top = H * (0.23f + row * 0.13f);
            p.setColor(0xFF5B3A22);
            c.drawRect(W * 0.80f, top + H * 0.115f, W * 0.93f, top + H * 0.13f, p);
            for (int k = 0; k < 9; k++) {
                p.setColor(bookColors[(row + k) % bookColors.length]);
                float x0 = W * 0.815f + k * W * 0.0115f;
                c.drawRect(x0, top + H * (0.02f + (k % 3) * 0.008f), x0 + W * 0.009f, top + H * 0.115f, p);
            }
        }
        return bm;
    }
}
