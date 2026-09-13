package com.example.soyomesaj;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private static final String SOYO_PACKAGE = "com.haflla.soulu";
    private static final String SOYO_LITE_PACKAGE = "com.haflla.soulu.lite";

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable statusLoop = new Runnable() {
        @Override
        public void run() {
            refreshStatus();
            uiHandler.postDelayed(this, 900L);
        }
    };

    private AppPrefs appPrefs;
    private SharedPreferences prefs;
    private HandledStore handledStore;

    private TextView serviceStatus;
    private TextView runtimeStatus;
    private TextView handledCount;
    private TextView preview;
    private Switch fullAuto;
    private Switch autoOpen;
    private Switch vipOnly;
    private Spinner titleSpinner;
    private EditText templateInput;

    private boolean bindingUi;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        appPrefs = new AppPrefs(this);
        prefs = appPrefs.raw();
        handledStore = new HandledStore(this);
        handledStore.cleanupExpired();

        serviceStatus = findViewById(R.id.textServiceStatus);
        runtimeStatus = findViewById(R.id.textRuntimeStatus);
        handledCount = findViewById(R.id.textHandledCount);
        preview = findViewById(R.id.textPreview);
        fullAuto = findViewById(R.id.switchFullAuto);
        autoOpen = findViewById(R.id.switchAutoOpen);
        vipOnly = findViewById(R.id.switchVipOnly);
        titleSpinner = findViewById(R.id.spinnerTitle);
        templateInput = findViewById(R.id.editTemplate);

        Button accessibility = findViewById(R.id.buttonAccessibility);
        Button openSoyo = findViewById(R.id.buttonOpenSoyo);
        Button resetHistory = findViewById(R.id.buttonResetHistory);

        ArrayAdapter<String> titleAdapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                new String[]{"Bey", "Hanım", "Hitap yok"});
        titleSpinner.setAdapter(titleAdapter);

        bindingUi = true;
        fullAuto.setChecked(appPrefs.fullAuto());
        autoOpen.setChecked(appPrefs.autoOpen());
        vipOnly.setChecked(appPrefs.vipOnly());
        titleSpinner.setSelection(appPrefs.titleIndex());
        templateInput.setText(appPrefs.template());
        bindingUi = false;

        fullAuto.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (bindingUi) return;
            prefs.edit().putBoolean(AppPrefs.KEY_FULL_AUTO, isChecked).apply();
            if (isChecked && !autoOpen.isChecked()) autoOpen.setChecked(true);
            Toast.makeText(this,
                    isChecked ? "Tam otomatik mod açıldı." : "Tam otomatik mod kapatıldı.",
                    Toast.LENGTH_SHORT).show();
        });

        autoOpen.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (bindingUi) return;
            if (!isChecked && fullAuto.isChecked()) {
                bindingUi = true;
                autoOpen.setChecked(true);
                bindingUi = false;
                Toast.makeText(this, "Tam otomatik modda sohbet açma açık olmalı.", Toast.LENGTH_SHORT).show();
                return;
            }
            prefs.edit().putBoolean(AppPrefs.KEY_AUTO_OPEN, isChecked).apply();
        });

        vipOnly.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (bindingUi) return;
            prefs.edit().putBoolean(AppPrefs.KEY_VIP_ONLY, isChecked).apply();
        });

        titleSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!bindingUi) prefs.edit().putInt(AppPrefs.KEY_TITLE, position).apply();
                refreshPreview();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        templateInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (!bindingUi) {
                    String value = s == null ? "" : s.toString();
                    prefs.edit().putString(AppPrefs.KEY_TEMPLATE,
                            value.trim().isEmpty() ? AppPrefs.DEFAULT_TEMPLATE : value).apply();
                }
                refreshPreview();
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        accessibility.setOnClickListener(v -> openAccessibilitySettings());
        openSoyo.setOnClickListener(v -> openSoyo());
        resetHistory.setOnClickListener(v -> {
            handledStore.clear();
            prefs.edit().putLong(AppPrefs.KEY_HISTORY_RESET, System.nanoTime()).apply();
            Toast.makeText(this, "İşlenen kişi geçmişi temizlendi.", Toast.LENGTH_SHORT).show();
            refreshStatus();
        });

        refreshPreview();
        refreshStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        uiHandler.removeCallbacks(statusLoop);
        uiHandler.post(statusLoop);
    }

    @Override
    protected void onPause() {
        super.onPause();
        uiHandler.removeCallbacks(statusLoop);
        saveMessageSettings();
    }

    private void saveMessageSettings() {
        String value = templateInput == null ? "" : templateInput.getText().toString().trim();
        if (value.isEmpty()) value = AppPrefs.DEFAULT_TEMPLATE;
        prefs.edit()
                .putString(AppPrefs.KEY_TEMPLATE, value)
                .putInt(AppPrefs.KEY_TITLE, titleSpinner == null ? 0 : titleSpinner.getSelectedItemPosition())
                .apply();
    }

    private void refreshPreview() {
        if (preview == null || appPrefs == null) return;
        preview.setText(appPrefs.buildMessage("Berk"));
    }

    private void refreshStatus() {
        if (serviceStatus == null) return;
        boolean enabled = isAccessibilityServiceEnabled();
        serviceStatus.setText(enabled ? "● Açık" : "○ Kapalı");
        serviceStatus.setTextColor(getColor(enabled ? R.color.success : R.color.danger));

        String current = prefs.getString(AppPrefs.KEY_RUNTIME_STATUS, "");
        if (TextUtils.isEmpty(current)) {
            current = enabled ? "SOYO ekranını bekliyor." : "Önce erişilebilirlik servisini aç.";
        }
        runtimeStatus.setText(current);
        handledCount.setText("Tekrar koruması: " + handledStore.count() + " kişi");
    }

    private boolean isAccessibilityServiceEnabled() {
        ComponentName expected = new ComponentName(this, SoyoAutomationService.class);
        String enabledServices = Settings.Secure.getString(
                getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(enabledServices)) return false;
        TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
        splitter.setString(enabledServices);
        while (splitter.hasNext()) {
            ComponentName enabled = ComponentName.unflattenFromString(splitter.next());
            if (expected.equals(enabled)) return true;
        }
        return false;
    }

    private void openAccessibilitySettings() {
        saveMessageSettings();
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            Toast.makeText(this, "Listeden SOYO Mesaj Yardımcısı'nı aç.", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Erişilebilirlik ayarları açılamadı.", Toast.LENGTH_SHORT).show();
        }
    }

    private void openSoyo() {
        saveMessageSettings();
        Intent launch = getPackageManager().getLaunchIntentForPackage(SOYO_PACKAGE);
        if (launch == null) launch = getPackageManager().getLaunchIntentForPackage(SOYO_LITE_PACKAGE);
        if (launch != null) {
            startActivity(launch);
            return;
        }
        Toast.makeText(this, "SOYO bulunamadı; mağaza açılıyor.", Toast.LENGTH_SHORT).show();
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + SOYO_PACKAGE)));
        } catch (Exception ignored) {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=" + SOYO_PACKAGE)));
        }
    }
}
