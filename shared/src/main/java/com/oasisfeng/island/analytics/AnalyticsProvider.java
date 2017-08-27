package com.oasisfeng.island.analytics;

import android.app.Activity;
import android.app.Application;
import android.content.ComponentName;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;

import com.google.common.base.Optional;
import com.google.firebase.FirebaseApp;
import com.oasisfeng.android.content.pm.Permissions;
import com.oasisfeng.island.shared.BuildConfig;
import com.oasisfeng.island.util.Hacks;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

import javax.annotation.ParametersAreNonnullByDefault;

import static android.os.Build.VERSION.SDK_INT;
import static android.os.Build.VERSION_CODES.M;

/**
 * Provider to support cross-profile analytics.
 *
 * Created by Oasis on 2017/3/21.
 */
@ParametersAreNonnullByDefault
public class AnalyticsProvider extends ContentProvider {

	static void enableIfNeeded(final Context context) {
		if (! Permissions.has(context, Hacks.Permission.INTERACT_ACROSS_USERS)) return;
		final PackageManager pm = context.getPackageManager();
		final ComponentName provider_component = new ComponentName(context, AnalyticsProvider.class);
		try {
			final ProviderInfo provider = pm.getProviderInfo(provider_component, PackageManager.GET_DISABLED_COMPONENTS);
			if (provider.isEnabled()) return;
		} catch (final PackageManager.NameNotFoundException e) { return; }
		pm.setComponentEnabledSetting(provider_component, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
	}

	static Analytics getClient(final Context context) {
		return Optional.fromNullable(getClientIfAvailable(context)).or(NULL_ANALYTICS);
	}

	static @Nullable Analytics getClientIfAvailable(final Context context) {
		if (SDK_INT < M) return null;
		try {
			final ComponentName provider_component = new ComponentName(context, AnalyticsProvider.class);
			final PackageManager pm = context.getPackageManager();
			@SuppressWarnings("deprecation") final ProviderInfo provider = pm.getProviderInfo(provider_component, PackageManager.GET_DISABLED_COMPONENTS);
			if (provider == null) throw new IllegalStateException("Provider not declared: " + AnalyticsProvider.class);
			final ProviderInfo remote_provider = pm.resolveContentProvider(provider.authority, 0);
			if (remote_provider == null) return null;		// Remote provider is not enabled or just enabled but its process is not yet restarted.
			return new AnalyticsClient(context, remote_provider);
		} catch (final PackageManager.NameNotFoundException e) { return null; }
	}

	@Nullable @Override public Uri insert(final Uri uri, final @Nullable ContentValues values) {
		if (values == null) return null;
		final Analytics analytics = Analytics.$();
		if (analytics instanceof AnalyticsClient) throw new IllegalStateException("Non-local Analytics implementation");
		final String path = uri.getPath();
		if (path.isEmpty()) {
			final String event_name = values.getAsString(null);
			if (event_name == null) return null;
			final Bundle bundle = new Bundle();
			for (final String key : values.keySet())
				if (key != null) bundle.putString(key, values.getAsString(key));
			analytics.reportEvent(event_name, bundle);
		} else if (path.equals(CONTENT_URI_SUFFIX_EXCEPTIONS)) {
			final byte[] serialized = values.getAsByteArray(null);
			final Throwable exception;
			try {
				exception = (Throwable) new ObjectInputStream(new ByteArrayInputStream(serialized)).readObject();
			} catch (IOException | ClassNotFoundException | RuntimeException e) {
				Log.e(TAG, "Failed to read remote Throwable object", e);
				return null;
			}
			analytics.report(exception);
		} else if (path.equals(CONTENT_URI_SUFFIX_PROPERTIES)) {
			for (final String key : values.keySet()) try {
				final int ordinal = Integer.parseInt(key);
				analytics.setProperty(Analytics.Property.values()[ordinal], values.getAsString(key));
			} catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
				if (BuildConfig.DEBUG) throw e;
			}
		}
		return null;
	}

	private static final String CONTENT_URI_SUFFIX_EXCEPTIONS = "/exceptions";
	private static final String CONTENT_URI_SUFFIX_PROPERTIES = "/properties";

	private static class AnalyticsClient implements Analytics {

		private final Uri CONTENT_URI;
		private final Uri CONTENT_URI_EXCEPTION;
		private final Uri CONTENT_URI_PROPERTIES;

		@Override public Event event(final String event) {
			final ContentValues bundle = new ContentValues();
			bundle.put(null, event);
			return new Event() {
				@Override public Event with(final Param key, final @Nullable String value) { bundle.put(key.key, value); return this; }
				@Override public void send() { context().getContentResolver().insert(CONTENT_URI, bundle); }
			};
		}

		@Override public void reportEvent(final String event, final Bundle params) {
			final ContentValues bundle = new ContentValues();
			for (final String key : params.keySet()) {
				final Object value = bundle.get(key);
				if (value instanceof String) bundle.put(key, (String) value);
				else if (value instanceof Boolean) bundle.put(key, (Boolean) value);
				else if (value instanceof Integer) bundle.put(key, (Integer) value);
				else if (value instanceof Long) bundle.put(key, (Long) value);
				else if (value instanceof Short) bundle.put(key, (Short) value);
				else if (value instanceof Double) bundle.put(key, (Double) value);
				else if (value instanceof Float) bundle.put(key, (Float) value);
				else if (value instanceof byte[]) bundle.put(key, (byte[]) value);
				else if (value instanceof Byte) bundle.put(key, (Byte) value);
			}
			context().getContentResolver().insert(CONTENT_URI, bundle);
		}

		@Override public void report(final Throwable t) {
			final ByteArrayOutputStream out = new ByteArrayOutputStream();
			try {
				new ObjectOutputStream(out).writeObject(t);
			} catch (final IOException ignored) { return; }		// Should never happen
			final ContentValues bundle = new ContentValues();
			bundle.put(null, out.toByteArray());
			context().getContentResolver().insert(CONTENT_URI_EXCEPTION, bundle);
		}

		@Override public void setProperty(final Property property, final String value) {
			final ContentValues values = new ContentValues(1);
			values.put(String.valueOf(property.ordinal()), value);
			context().getContentResolver().insert(CONTENT_URI_PROPERTIES, values);
		}

		private Context context() { return mContext; }

		private AnalyticsClient(final Context context, final ProviderInfo provider) {
			mContext = context;
			final String prefix = "content://" + provider.authority;
			CONTENT_URI = Uri.parse(prefix);
			CONTENT_URI_EXCEPTION = Uri.parse(prefix + CONTENT_URI_SUFFIX_EXCEPTIONS);
			CONTENT_URI_PROPERTIES = Uri.parse(prefix + CONTENT_URI_SUFFIX_PROPERTIES);
		}

		private final Context mContext;
	}

	private static final Analytics NULL_ANALYTICS = new Analytics() {
		private final Event NULL_EVENT = new Event() {
			@Override public Event with(final Param key, final @Nullable String value) { return this; }
			@Override public void send() {}
		};
		@Override public Event event(final String event) { return NULL_EVENT; }
		@Override public void reportEvent(final String event, final Bundle params) {}
		@Override public void report(final Throwable t) {}
		@Override public void setProperty(final Property property, final String value) {}
	};

	@Override public boolean onCreate() {		// As lightweight as possible in initialization.
		final Context context = getContext(), app_context;
		if (context != null && (app_context = context.getApplicationContext()) instanceof Application) {
			final Application app = (Application) app_context;
			app.registerActivityLifecycleCallbacks(new LazyInitActivityLifecycleCallbacks(app));
		}
		return true;
	}

	/* The following methods are not implemented. */
	@Nullable @Override public String getType(final @NonNull Uri uri) { return null; }
	@Override public int delete(final @NonNull Uri uri, final @Nullable String s, final @Nullable String[] strings) { return 0; }
	@Override public int update(final @NonNull Uri uri, final @Nullable ContentValues contentValues, final @Nullable String s, final @Nullable String[] strings) { return 0; }
	@Nullable @Override public Cursor query(final @NonNull Uri uri, final @Nullable String[] projection, final @Nullable String selection, final @Nullable String[] selection_args, final @Nullable String sort) { return null; }

	private static final String TAG = "Analytics.Agent";

	private static class LazyInitActivityLifecycleCallbacks implements Application.ActivityLifecycleCallbacks {

		@Override public void onActivityCreated(final Activity activity, final Bundle savedInstanceState) {
			app.unregisterActivityLifecycleCallbacks(this);
			FirebaseApp.initializeApp(activity.getApplicationContext());
		}

		@Override public void onActivityStopped(final Activity activity) {}
		@Override public void onActivityStarted(final Activity activity) {}
		@Override public void onActivitySaveInstanceState(final Activity activity, final Bundle outState) {}
		@Override public void onActivityResumed(final Activity activity) {}
		@Override public void onActivityPaused(final Activity activity) {}
		@Override public void onActivityDestroyed(final Activity activity) {}
		LazyInitActivityLifecycleCallbacks(final Application app) { this.app = app; }

		private final Application app;
	}
}
