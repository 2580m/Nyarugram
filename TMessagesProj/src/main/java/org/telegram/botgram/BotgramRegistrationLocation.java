package org.telegram.botgram;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Criteria;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** One registration-only approximate fix; only the reverse-geocoded country goes to the server. */
public final class BotgramRegistrationLocation implements LocationListener {
    public static final int PERMISSION_REQUEST = 0xB072;
    private final Activity activity;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService geocoder = Executors.newSingleThreadExecutor();
    private LocationManager manager;
    private Consumer<String> callback;
    private boolean resolving, disposed;
    private int generation;
    private final Runnable timeout = () -> finish(null);

    public BotgramRegistrationLocation(Activity activity) { this.activity = activity; }
    public void resolve(Consumer<String> callback) {
        generation++;
        resolving = false;
        this.callback = callback;
        if (activity == null) { finish(null); return; }
        if (activity.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            try { activity.requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, PERMISSION_REQUEST); }
            catch (Exception e) { finish(null); }
        } else locate();
    }
    public void onPermissionsResult(int[] results) {
        if (callback == null || disposed) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) locate();
        else finish(null);
    }
    private void locate() {
        handler.postDelayed(timeout, 12_000);
        try {
            manager = (LocationManager) activity.getSystemService(Activity.LOCATION_SERVICE);
            if (manager == null || !Geocoder.isPresent()) { finish(null); return; }
            Location latest = null;
            for (String provider : manager.getProviders(true)) {
                try {
                    Location value = manager.getLastKnownLocation(provider);
                    if (value != null && (latest == null || value.getElapsedRealtimeNanos() > latest.getElapsedRealtimeNanos())) latest = value;
                } catch (SecurityException ignored) { }
            }
            if (latest != null && android.os.SystemClock.elapsedRealtimeNanos() - latest.getElapsedRealtimeNanos() < 600_000_000_000L) {
                onLocationChanged(latest); return;
            }
            Criteria criteria = new Criteria(); criteria.setAccuracy(Criteria.ACCURACY_COARSE);
            String provider = manager.getBestProvider(criteria, true);
            if (provider == null) { finish(null); return; }
            manager.requestSingleUpdate(provider, this, Looper.getMainLooper());
        } catch (Exception e) { finish(null); }
    }
    @Override public void onLocationChanged(Location location) {
        if (resolving || callback == null || disposed) return;
        resolving = true;
        int requestGeneration = generation;
        removeUpdates();
        geocoder.execute(() -> {
            String country = null;
            try {
                List<Address> addresses = new Geocoder(activity, Locale.US).getFromLocation(location.getLatitude(), location.getLongitude(), 1);
                if (addresses != null && !addresses.isEmpty()) country = addresses.get(0).getCountryCode();
            } catch (Exception ignored) { }
            String result = country;
            handler.post(() -> { if (requestGeneration == generation) finish(result); });
        });
    }
    private void removeUpdates() { try { if (manager != null) manager.removeUpdates(this); } catch (Exception ignored) { } }
    private void finish(String country) {
        handler.removeCallbacks(timeout); removeUpdates();
        Consumer<String> done = callback; callback = null;
        if (!disposed && done != null) done.accept(country);
    }
    public void dispose() { disposed = true; callback = null; handler.removeCallbacksAndMessages(null); removeUpdates(); geocoder.shutdownNow(); }
    @Override public void onProviderDisabled(String provider) { finish(null); }
    @Override public void onProviderEnabled(String provider) { }
    @Override public void onStatusChanged(String provider, int status, Bundle extras) { }
}
