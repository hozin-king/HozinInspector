package com.hozinking.appinspector.ui;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.hozinking.appinspector.R;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Mode view gambar full-screen (Hozin Tools).
 *
 * Kontrak intent extras:
 * - EXTRA_IMG_URL ("img_url"): unduh dari URL ini (background thread).
 * - EXTRA_IMG_PATH ("img_path"): file lokal — dipakai bila ada.
 *
 * Gestur:
 * - pinch zoom in/out (1x - 4x)
 * - pan (geser) saat zoom > 1x
 * - double-tap: toggle zoom 1x <-> 2.5x di titik yang di-tap
 * - tap sekali: keluar
 * - tombol X di pojok: keluar
 *
 * Tanpa library eksternal: Matrix + ScaleGestureDetector + GestureDetector.
 * Decode di-downsample (maks 2048px) agar tidak OOM.
 */
public class ImageViewerActivity extends AppCompatActivity {

    public static final String EXTRA_IMG_URL = "img_url";
    public static final String EXTRA_IMG_PATH = "img_path";

    private static final String UA =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    private static final float MIN_SCALE = 1f;
    private static final float MAX_SCALE = 4f;
    private static final float DOUBLE_TAP_SCALE = 2.5f;
    private static final int MAX_DIM = 2048;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private ImageView ivZoom;
    private ProgressBar progress;
    private TextView tvError, tvHint, btnClose;

    private Bitmap bitmap;
    private final Matrix matrix = new Matrix();
    private final float[] mVals = new float[9];
    private float saveScale = MIN_SCALE;
    private float bmpW, bmpH;
    private int viewW, viewH;

    private ScaleGestureDetector scaleDetector;
    private GestureDetector gestureDetector;
    private final PointF lastPt = new PointF();
    private boolean dragging = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_image_viewer);

        ivZoom = findViewById(R.id.ivZoom);
        progress = findViewById(R.id.progressImage);
        tvError = findViewById(R.id.tvImageError);
        tvHint = findViewById(R.id.tvImageHint);
        btnClose = findViewById(R.id.btnCloseViewer);

        ivZoom.setScaleType(ImageView.ScaleType.MATRIX);

        scaleDetector = new ScaleGestureDetector(this, new ScaleListener());
        gestureDetector = new GestureDetector(this, new GestureListener());
        ivZoom.setOnTouchListener((v, e) -> onTouchImage(e));

        btnClose.setOnClickListener(v -> finish());

        String path = getIntent().getStringExtra(EXTRA_IMG_PATH);
        String url = getIntent().getStringExtra(EXTRA_IMG_URL);
        loadImage(path, url);
    }

    // ---------------------------------------------------------------- gestur

    private boolean onTouchImage(MotionEvent e) {
        scaleDetector.onTouchEvent(e);
        gestureDetector.onTouchEvent(e);

        int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            lastPt.set(e.getX(), e.getY());
            dragging = true;
            hideHint();
        } else if (action == MotionEvent.ACTION_POINTER_DOWN) {
            dragging = false; // pinch dimulai — matikan drag 1 jari
        } else if (action == MotionEvent.ACTION_MOVE) {
            if (dragging && e.getPointerCount() == 1
                    && !scaleDetector.isInProgress()
                    && bitmap != null && saveScale > MIN_SCALE + 0.01f) {
                float dx = e.getX() - lastPt.x;
                float dy = e.getY() - lastPt.y;
                matrix.postTranslate(dx, dy);
                fixTrans();
                ivZoom.setImageMatrix(matrix);
            }
            lastPt.set(e.getX(), e.getY());
        } else if (action == MotionEvent.ACTION_UP
                || action == MotionEvent.ACTION_CANCEL) {
            dragging = false;
        }
        return true;
    }

    private class GestureListener extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onDown(MotionEvent e) {
            return true;
        }

        /** Tap sekali = keluar dari mode view. */
        @Override
        public boolean onSingleTapConfirmed(MotionEvent e) {
            finish();
            return true;
        }

        /** Double-tap = toggle zoom 1x <-> 2.5x di titik tap. */
        @Override
        public boolean onDoubleTap(MotionEvent e) {
            toggleZoom(e.getX(), e.getY());
            return true;
        }
    }

    private class ScaleListener
            extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScale(ScaleGestureDetector d) {
            if (bitmap == null) return true;
            float factor = d.getScaleFactor();
            float newScale = saveScale * factor;
            if (newScale > MAX_SCALE) {
                factor = MAX_SCALE / saveScale;
                newScale = MAX_SCALE;
            } else if (newScale < MIN_SCALE) {
                factor = MIN_SCALE / saveScale;
                newScale = MIN_SCALE;
            }
            if (factor != 1f) {
                matrix.postScale(factor, factor, d.getFocusX(), d.getFocusY());
                saveScale = newScale;
                fixTrans();
                ivZoom.setImageMatrix(matrix);
            }
            return true;
        }
    }

    private void toggleZoom(float x, float y) {
        if (bitmap == null || viewW <= 0 || viewH <= 0) return;
        hideHint();
        if (saveScale > MIN_SCALE + 0.01f) {
            fitToScreen();
        } else {
            float factor = DOUBLE_TAP_SCALE / saveScale;
            matrix.postScale(factor, factor, x, y);
            saveScale = DOUBLE_TAP_SCALE;
            fixTrans();
            ivZoom.setImageMatrix(matrix);
        }
    }

    /** Pas-kan gambar ke layar (zoom = 1x). */
    private void fitToScreen() {
        matrix.reset();
        float scale = Math.min(viewW / bmpW, viewH / bmpH);
        matrix.postScale(scale, scale);
        matrix.postTranslate((viewW - bmpW * scale) / 2f,
                (viewH - bmpH * scale) / 2f);
        ivZoom.setImageMatrix(matrix);
        saveScale = MIN_SCALE;
    }

    /**
     * Koreksi translasi: gambar tidak boleh keluar batas layar saat di-zoom,
     * dan tetap di tengah saat lebih kecil dari layar.
     */
    private void fixTrans() {
        matrix.getValues(mVals);
        float transX = mVals[Matrix.MTRANS_X];
        float transY = mVals[Matrix.MTRANS_Y];
        float scale = mVals[Matrix.MSCALE_X];
        float fixX = fixAxis(transX, viewW, bmpW * scale);
        float fixY = fixAxis(transY, viewH, bmpH * scale);
        if (fixX != 0 || fixY != 0) {
            matrix.postTranslate(fixX, fixY);
        }
    }

    private static float fixAxis(float trans, float viewSize, float imgSize) {
        if (imgSize <= viewSize) {
            return (viewSize - imgSize) / 2f - trans; // tengahkan
        }
        float minTrans = viewSize - imgSize;
        if (trans < minTrans) return minTrans - trans;
        if (trans > 0) return -trans;
        return 0;
    }

    private void hideHint() {
        if (tvHint.getVisibility() == View.VISIBLE) {
            tvHint.setVisibility(View.GONE);
        }
    }

    // ------------------------------------------------------------------ muat

    private void loadImage(String path, String url) {
        progress.setVisibility(View.VISIBLE);
        tvError.setVisibility(View.GONE);
        new Thread(() -> {
            Bitmap bmp = null;
            try {
                if (path != null && !path.isEmpty()) {
                    bmp = decodeFileScaled(path, MAX_DIM);
                } else if (url != null && !url.isEmpty()) {
                    bmp = fetchBitmap(url, MAX_DIM);
                }
            } catch (OutOfMemoryError oom) {
                bmp = null;
            } catch (Exception ignored) {
                bmp = null;
            }
            final Bitmap fbm = bmp;
            handler.post(() -> {
                progress.setVisibility(View.GONE);
                if (isFinishing()) return;
                if (fbm == null) {
                    tvError.setVisibility(View.VISIBLE);
                    Toast.makeText(this, "Gagal memuat gambar",
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                bitmap = fbm;
                bmpW = fbm.getWidth();
                bmpH = fbm.getHeight();
                ivZoom.setImageBitmap(fbm);
                ivZoom.post(() -> {
                    viewW = ivZoom.getWidth();
                    viewH = ivZoom.getHeight();
                    if (viewW > 0 && viewH > 0) fitToScreen();
                });
            });
        }).start();
    }

    /** Decode file lokal dengan downsample (batas dimensi maks). */
    private static Bitmap decodeFileScaled(String path, int maxDim) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, o);
            if (o.outWidth <= 0 || o.outHeight <= 0) return null;
            int sample = 1;
            while (Math.max(o.outWidth, o.outHeight) / sample > maxDim) {
                sample *= 2;
            }
            o.inJustDecodeBounds = false;
            o.inSampleSize = sample;
            return BitmapFactory.decodeFile(path, o);
        } catch (Exception e) {
            return null;
        }
    }

    /** Unduh bitmap dengan downsample (batas dimensi maks), null bila gagal. */
    private static Bitmap fetchBitmap(String url, int maxDim) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", UA);
            if (conn.getResponseCode() / 100 != 2) return null;

            InputStream in = conn.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n, total = 0;
            while ((n = in.read(buf)) != -1) {
                total += n;
                if (total > 12 * 1024 * 1024) return null;
                bos.write(buf, 0, n);
            }
            in.close();
            byte[] data = bos.toByteArray();
            if (data.length == 0) return null;

            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            if (o.outWidth <= 0 || o.outHeight <= 0) return null;
            int sample = 1;
            while (Math.max(o.outWidth, o.outHeight) / sample > maxDim) {
                sample *= 2;
            }
            o.inJustDecodeBounds = false;
            o.inSampleSize = sample;
            return BitmapFactory.decodeByteArray(data, 0, data.length, o);
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    @Override
    protected void onDestroy() {
        if (bitmap != null && !bitmap.isRecycled()) {
            bitmap.recycle();
            bitmap = null;
        }
        super.onDestroy();
    }
}
