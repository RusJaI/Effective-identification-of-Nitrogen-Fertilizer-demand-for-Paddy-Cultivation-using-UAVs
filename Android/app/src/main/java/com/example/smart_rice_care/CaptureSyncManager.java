package com.example.smart_rice_care;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import com.google.firebase.auth.FirebaseAuth;

import java.io.File;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Saves each capture before trying the server and keeps failures in a durable SQLite outbox. */
public final class CaptureSyncManager {
    public interface Callback {
        void onPredicted(int level);
        void onQueued();
    }

    public interface BatchCallback {
        void onCompleted(int predictedCount, List<CaptureRecord> remaining, boolean attempted);
    }

    public interface PendingCallback {
        void onLoaded(List<CaptureRecord> pending);
    }

    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ConcurrentHashMap<String, InFlightCapture> IN_FLIGHT = new ConcurrentHashMap<>();

    private static final class InFlightCapture {
        final CaptureRecord capture;
        final CountDownLatch finished = new CountDownLatch(1);

        InFlightCapture(CaptureRecord capture) {
            this.capture = capture;
        }
    }

    private CaptureSyncManager() { }

    public static void saveAndUpload(Context context, File image, String requestId,
                                     double latitude, double longitude, long timestamp,
                                     Callback callback) {
        Context appContext = context.getApplicationContext();
        String id = UUID.randomUUID().toString();
        String officerId = FirebaseAuth.getInstance().getUid();
        CaptureRecord capture = new CaptureRecord(id, image.getAbsolutePath(), requestId,
                officerId == null ? "" : officerId, latitude, longitude, timestamp);
        CaptureQueue queue = new CaptureQueue(appContext);
        try {
            queue.add(capture);
        } finally {
            queue.close();
        }

        if (!hasNetwork(appContext)) {
            MAIN.post(callback::onQueued);
            return;
        }

        InFlightCapture inFlight = new InFlightCapture(capture);
        IN_FLIGHT.put(id, inFlight);
        EXECUTOR.execute(() -> {
            try {
                int level = CaptureUploadClient.upload(appContext, capture);
                MAIN.post(() -> callback.onPredicted(level));
            } catch (Exception e) {
                MAIN.post(callback::onQueued);
            } finally {
                IN_FLIGHT.remove(id);
                inFlight.finished.countDown();
            }
        });
    }

    public static void processPendingForInspection(Context context, String requestId, BatchCallback callback) {
        Context appContext = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            try {
                for (InFlightCapture active : IN_FLIGHT.values()) {
                    if (requestId.equals(active.capture.requestId)) {
                        active.finished.await(90, TimeUnit.SECONDS);
                    }
                }
                List<CaptureRecord> pending = getPending(appContext, requestId);
                if (pending.isEmpty()) {
                    MAIN.post(() -> callback.onCompleted(0, pending, false));
                    return;
                }
                if (!hasNetwork(appContext)) {
                    MAIN.post(() -> callback.onCompleted(0, pending, false));
                    return;
                }

                int predicted = 0;
                for (CaptureRecord capture : pending) {
                    if (!hasNetwork(appContext)) break;
                    try {
                        CaptureUploadClient.upload(appContext, capture);
                        predicted++;
                    } catch (Exception ignored) {
                        // Keep failures queued; the user can explicitly retry from the inspection report.
                    }
                }
                List<CaptureRecord> remaining = getPending(appContext, requestId);
                int predictedCount = predicted;
                MAIN.post(() -> callback.onCompleted(predictedCount, remaining, true));
            } catch (Exception e) {
                List<CaptureRecord> remaining;
                try {
                    remaining = getPending(appContext, requestId);
                } catch (Exception ignored) {
                    remaining = java.util.Collections.emptyList();
                }
                List<CaptureRecord> result = remaining;
                MAIN.post(() -> callback.onCompleted(0, result, false));
            }
        });
    }

    public static void getPendingForInspection(Context context, String requestId, PendingCallback callback) {
        Context appContext = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            List<CaptureRecord> pending;
            try {
                pending = getPending(appContext, requestId);
            } catch (Exception e) {
                pending = java.util.Collections.emptyList();
            }
            List<CaptureRecord> result = pending;
            MAIN.post(() -> callback.onLoaded(result));
        });
    }

    private static List<CaptureRecord> getPending(Context context, String requestId) {
        CaptureQueue queue = new CaptureQueue(context);
        try {
            return queue.getPendingForRequest(requestId);
        } finally {
            queue.close();
        }
    }

    static boolean hasNetwork(Context context) {
        ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Network network = manager.getActiveNetwork();
            NetworkCapabilities capabilities = network == null ? null : manager.getNetworkCapabilities(network);
            return capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        }
        NetworkInfo info = manager.getActiveNetworkInfo();
        return info != null && info.isConnected();
    }
}
