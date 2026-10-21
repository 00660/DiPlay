package com.shihab.diplay.tvlauncher;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Lightweight Android 4.4-compatible TV home screen, independent of the CarPlay receiver. */
public final class TvLauncherActivity extends Activity {
    private static final int BG = Color.rgb(12, 17, 27);
    private static final int CARD = Color.rgb(25, 34, 48);
    private static final int ACCENT = Color.rgb(102, 217, 195);
    private GridLayout appGrid;
    private TextView appCount;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        buildScreen();
        loadApps();
    }

    @Override protected void onResume() {
        super.onResume();
        if (appGrid != null) loadApps();
    }

    private void buildScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(42), dp(28), dp(42), dp(24));
        root.setBackgroundColor(BG);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("DiPlay");
        title.setTextColor(Color.WHITE);
        title.setTextSize(30);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        appCount = new TextView(this);
        appCount.setTextColor(Color.rgb(164, 177, 195));
        appCount.setTextSize(14);
        header.addView(appCount);
        Button refresh = new Button(this);
        refresh.setText("刷新");
        refresh.setTextColor(Color.WHITE);
        refresh.setOnClickListener(v -> loadApps());
        header.addView(refresh);
        root.addView(header, new LinearLayout.LayoutParams(-1, dp(56)));

        TextView section = new TextView(this);
        section.setText("应用");
        section.setTextColor(ACCENT);
        section.setTextSize(16);
        section.setPadding(0, dp(18), 0, dp(12));
        root.addView(section);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        appGrid = new GridLayout(this);
        appGrid.setColumnCount(5);
        appGrid.setAlignmentMode(GridLayout.ALIGN_BOUNDS);
        scroll.addView(appGrid, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }

    private void loadApps() {
        if (appGrid == null) return;
        PackageManager pm = getPackageManager();
        List<ApplicationInfo> apps = new ArrayList<ApplicationInfo>();
        for (ApplicationInfo info : pm.getInstalledApplications(PackageManager.GET_META_DATA)) {
            if (info.packageName.equals(getPackageName())) continue;
            if (pm.getLaunchIntentForPackage(info.packageName) != null) apps.add(info);
        }
        Collections.sort(apps, new Comparator<ApplicationInfo>() {
            @Override public int compare(ApplicationInfo a, ApplicationInfo b) {
                return pm.getApplicationLabel(a).toString().compareToIgnoreCase(pm.getApplicationLabel(b).toString());
            }
        });
        appGrid.removeAllViews();
        appCount.setText(apps.size() + " 个应用");
        for (final ApplicationInfo info : apps) appGrid.addView(createAppCard(info, pm));
        if (appGrid.getChildCount() > 0) appGrid.getChildAt(0).requestFocus();
    }

    private View createAppCard(final ApplicationInfo info, final PackageManager pm) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setFocusable(true);
        card.setFocusableInTouchMode(true);
        card.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD);
        bg.setCornerRadius(dp(14));
        card.setBackgroundDrawable(bg);

        ImageView icon = new ImageView(this);
        icon.setImageDrawable(pm.getApplicationIcon(info));
        card.addView(icon, new LinearLayout.LayoutParams(dp(54), dp(54)));

        TextView label = new TextView(this);
        label.setText(pm.getApplicationLabel(info));
        label.setTextColor(Color.WHITE);
        label.setTextSize(15);
        label.setGravity(Gravity.CENTER);
        label.setMaxLines(1);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(-1, -2);
        textParams.topMargin = dp(8);
        card.addView(label, textParams);

        card.setOnFocusChangeListener((v, focused) -> {
            v.setScaleX(focused ? 1.06f : 1f);
            v.setScaleY(focused ? 1.06f : 1f);
            v.setElevation(focused ? dp(8) : 0);
            GradientDrawable focusedBg = new GradientDrawable();
            focusedBg.setColor(focused ? Color.rgb(37, 59, 70) : CARD);
            focusedBg.setCornerRadius(dp(14));
            focusedBg.setStroke(focused ? dp(2) : 0, ACCENT);
            v.setBackgroundDrawable(focusedBg);
        });
        card.setOnClickListener(v -> {
            Intent launch = pm.getLaunchIntentForPackage(info.packageName);
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try { startActivity(launch); }
                catch (RuntimeException ignored) { }
            }
        });

        GridLayout.LayoutParams params = new GridLayout.LayoutParams();
        params.width = dp(174);
        params.height = dp(132);
        params.setMargins(dp(7), dp(7), dp(7), dp(7));
        card.setLayoutParams(params);
        return card;
    }

    private int dp(float value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }
}
