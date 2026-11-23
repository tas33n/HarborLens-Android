package com.harborlens.app;

import android.content.Context;
import android.webkit.CookieManager;
import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import org.json.JSONArray;
import java.io.IOException;

public final class LookupWorker extends Worker {
    static final String STORE = "cpa-results", TAG = "cpa-lookup";
    public LookupWorker(@NonNull Context context, @NonNull WorkerParameters parameters) {
        super(context, parameters);
    }

    @NonNull @Override public Result doWork() {
        String code = getInputData().getString("container");
        if (code == null || !ContainerNumber.valid(code)) return Result.failure();
        var store = getApplicationContext().getSharedPreferences(STORE, Context.MODE_PRIVATE);
        try {
            String cookie = CookieManager.getInstance().getCookie(Cpa.ROOT);
            String[] row;
            try {
                row = Cpa.lookup(code, cookie);
            } catch (Cpa.Auth expired) {
                if (!CpaAccount.configured(getApplicationContext())) throw expired;
                String renewed = CpaAccount.signIn(getApplicationContext(), cookie);
                row = Cpa.lookup(code, renewed);
            }
            JSONArray json = new JSONArray();
            for (String value : row) json.put(value);
            return store.edit().putString(code, json.toString()).remove(code + "-error").commit()
                ? Result.success() : Result.retry();
        } catch (Cpa.Review review) {
            store.edit().putString(code + "-error", review.getMessage()).commit();
            return Result.failure();
        } catch (IOException unavailable) {
            if (getRunAttemptCount() < 3) return Result.retry();
            store.edit().putString(code + "-error", "CPA unavailable · tap Retry CPA").commit();
            return Result.failure();
        }
    }
}
