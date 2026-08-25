package com.liskovsoft.smartyoutubetv2.common.utils;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.AppDialogPresenter;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.prefs.VotData;
import com.liskovsoft.smartyoutubetv2.common.vot.VotCodeAuth;
import com.liskovsoft.smartyoutubetv2.common.vot.VotCodeAuth.Event;
import com.liskovsoft.smartyoutubetv2.common.vot.VotCodeAuth.Phase;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;

public final class VotQrAuthDialog {
    private static final String TAG = VotQrAuthDialog.class.getSimpleName();
    private static final int QR_SIZE = 800;
    private static final int QR_MARGIN = 4;

    private VotQrAuthDialog() {
    }

    public static void show(Context context) {
        // Close the settings panel stack: the sidebar otherwise stays on top of the QR view.
        AppDialogPresenter.instance(context).closeDialog();
        AlertDialog.Builder builder = new AlertDialog.Builder(context, R.style.AppDialog);
        View contentView = LayoutInflater.from(context).inflate(R.layout.vot_qr_auth_dialog, null);

        ImageView qrImage = contentView.findViewById(R.id.iv_qr_image);
        TextView codeText = contentView.findViewById(R.id.tv_qr_code);
        TextView statusText = contentView.findViewById(R.id.tv_qr_status);
        TextView countdownText = contentView.findViewById(R.id.tv_qr_countdown);
        Button refreshBtn = contentView.findViewById(R.id.btn_qr_refresh);
        Button manualBtn = contentView.findViewById(R.id.btn_qr_manual);
        Button cancelBtn = contentView.findViewById(R.id.btn_qr_cancel);

        Handler handler = new Handler(Looper.getMainLooper());
        AlertDialog[] dialogHolder = new AlertDialog[1];
        Disposable[] disposableHolder = new Disposable[1];
        Runnable[] countdownHolder = new Runnable[1];

        Runnable stopCountdown = () -> {
            if (countdownHolder[0] != null) {
                handler.removeCallbacks(countdownHolder[0]);
            }
        };

        Runnable unsubscribe = () -> {
            if (disposableHolder[0] != null && !disposableHolder[0].isDisposed()) {
                disposableHolder[0].dispose();
            }
        };

        // Re-run to (re)subscribe to the cold auth observable.
        Runnable subscribe = () -> {
            unsubscribe.run();
            stopCountdown.run();

            Observable<Event> observable = VotCodeAuth.create(VotData.instance(context).getRelayUrl())
                    .observeOn(AndroidSchedulers.mainThread());

            disposableHolder[0] = observable.subscribe(event -> {
                switch (event.phase) {
                    case WAITING:
                        statusText.setText(R.string.vot_qr_state_waiting);
                        codeText.setText(event.code);
                        codeText.setVisibility(View.VISIBLE);
                        Bitmap qrBitmap = createQrBitmap(event.detail);
                        if (qrBitmap != null) {
                            qrImage.setVisibility(View.VISIBLE);
                            qrImage.setImageBitmap(qrBitmap);
                        } else {
                            qrImage.setVisibility(View.GONE);
                        }
                        startCountdown(handler, countdownHolder, countdownText, event.expiresAtMs);
                        break;
                    case CONFIRMED_EXCHANGING:
                        stopCountdown.run();
                        qrImage.setVisibility(View.GONE);
                        codeText.setVisibility(View.GONE);
                        statusText.setText(R.string.vot_qr_state_saving);
                        break;
                    case SUCCESS:
                        stopCountdown.run();
                        VotData.instance(context).setOAuthToken(event.token);
                        MessageHelpers.showMessage(context, R.string.vot_token_saved);
                        dialogHolder[0].dismiss();
                        break;
                    case EXPIRED:
                        stopCountdown.run();
                        qrImage.setVisibility(View.GONE);
                        codeText.setVisibility(View.GONE);
                        statusText.setText(R.string.vot_qr_expired);
                        refreshBtn.requestFocus();
                        break;
                    case NETWORK_ERROR:
                        stopCountdown.run();
                        qrImage.setVisibility(View.GONE);
                        codeText.setVisibility(View.GONE);
                        statusText.setText(R.string.vot_qr_error_generic);
                        break;
                }
            });
        };

        refreshBtn.setOnClickListener(v -> subscribe.run());

        manualBtn.setOnClickListener(v -> {
            dialogHolder[0].dismiss();
            showManualTokenDialog(context);
        });

        cancelBtn.setOnClickListener(v -> dialogHolder[0].dismiss());

        AlertDialog dialog = builder
                .setTitle(R.string.vot_qr_title)
                .setView(contentView)
                .create();

        dialog.setOnDismissListener(d -> {
            unsubscribe.run();
            handler.removeCallbacksAndMessages(null);
        });
        dialog.setOnCancelListener(d -> {
            unsubscribe.run();
            handler.removeCallbacksAndMessages(null);
        });

        dialogHolder[0] = dialog;

        try {
            dialog.show();
        } catch (RuntimeException e) {
            Log.e(TAG, "Could not show QR auth dialog: " + e.getMessage());
            return;
        }

        subscribe.run();
    }

    private static void showManualTokenDialog(Context context) {
        VotTokenEditDialog.show(context, VotData.instance(context).getOAuthToken(), token -> {
            if (token.isEmpty()) {
                VotData.instance(context).clearOAuthToken();
                VotData.instance(context).setLivelyVoiceEnabled(false);
                MessageHelpers.showMessage(context, R.string.vot_token_cleared);
            } else {
                VotData.instance(context).setOAuthToken(token);
                MessageHelpers.showMessage(context, R.string.vot_token_saved);
            }
        });
    }

    private static void startCountdown(Handler handler, Runnable[] countdownHolder,
                                       TextView countdownText, long expiresAtMs) {
        countdownHolder[0] = new Runnable() {
            @Override
            public void run() {
                long remainingMs = expiresAtMs - System.currentTimeMillis();
                if (remainingMs <= 0) {
                    countdownText.setText(String.format(Locale.US, "%d:%02d", 0L, 0L));
                    return;
                }
                long minutes = TimeUnit.MILLISECONDS.toMinutes(remainingMs);
                long seconds = TimeUnit.MILLISECONDS.toSeconds(remainingMs) % 60;
                countdownText.setText(String.format(Locale.US, "%d:%02d", minutes, seconds));
                handler.postDelayed(this, TimeUnit.SECONDS.toMillis(1));
            }
        };
        handler.post(countdownHolder[0]);
    }

    private static Bitmap createQrBitmap(String content) {
        if (content == null || content.isEmpty()) {
            return null;
        }

        try {
            Map<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.MARGIN, QR_MARGIN);

            BitMatrix matrix = new QRCodeWriter()
                    .encode(content, BarcodeFormat.QR_CODE, QR_SIZE, QR_SIZE, hints);

            Bitmap bitmap = Bitmap.createBitmap(QR_SIZE, QR_SIZE, Bitmap.Config.ARGB_8888);
            for (int x = 0; x < QR_SIZE; x++) {
                for (int y = 0; y < QR_SIZE; y++) {
                    bitmap.setPixel(x, y, matrix.get(x, y) ? Color.parseColor("#FF1B1B1B") : Color.WHITE);
                }
            }
            return bitmap;
        } catch (Exception e) {
            Log.e(TAG, "Could not encode QR code: " + e.getMessage());
            return null;
        }
    }
}
