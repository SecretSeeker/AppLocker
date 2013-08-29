package com.twinone.locker;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.support.v4.app.NotificationCompat;
import android.util.Log;

public class ObserverService extends Service {

	public static final String TAG = "Observer";
	public static final String PREF_FILE_DEFAULT = "com.twinone.locker.prefs.default";
	private static final String PREF_FILE_APPS = "com.twinone.locker.prefs.apps";
	public static final String EXTRA_MESSAGE = "com.twinone.locker.extra.Message";
	public static final String EXTRA_TARGET_PACKAGENAME = "com.twinone.locker.extra.packageName";
	private static final String LOCKER_CLASS = LockActivity.class.getName();
	private static final boolean WHATSAPP_WORKAROUND = true; // TODO remove
	private static final long WHATSAPP_WAIT_DELAY = 500;
	private static final int NOTIFICATION_ID = 1337;

	private ActivityManager mAM;
	private ScheduledExecutorService mScheduledExecutor;
	private BroadcastReceiver mScreenReceiver;
	private HashSet<AppInfo> mTrackedApps;

	@SuppressWarnings("unused")
	private boolean mScreenOn = true;
	private String mPassword;
	private String mLastApp = "";
	private String mLastClass = "";
	private boolean mRelockAfterScreenOff;
	private boolean mDelayUnlockEnabled;
	private long mDelayUnlockRelockMillis;
	private Handler mDelayUnlockHandler;
	private boolean mShowNotification;
	/** Used for transparent notification icon */
	private int mNotificationPriority;
	private boolean mExplicitStarted;

	@Override
	public IBinder onBind(Intent i) {
		return new LocalBinder();
	}

	public class LocalBinder extends Binder {
		public ObserverService getInstance() {
			return ObserverService.this;
		}
	}

	@Override
	public void onCreate() {
		super.onCreate();
		Log.v(TAG, "onCreate");
		mAM = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
		mScreenReceiver = new ScreenReceiver();
		IntentFilter filter = new IntentFilter();
		filter.addAction(Intent.ACTION_SCREEN_ON);
		filter.addAction(Intent.ACTION_SCREEN_OFF);
		registerReceiver(mScreenReceiver, filter);
	}

	/**
	 * Shows the notification and starts the service foreground
	 */
	private void startNotification() {
		// Cancel previous notifications
		// NotificationManager does not work.
		// NotificationManager nm = (NotificationManager)
		// getSystemService(NOTIFICATION_SERVICE);
		// nm.cancel(NOTIFICATION_ID);
		stopForeground(true);

		if (mShowNotification) {
			Intent i = new Intent(this, MainActivity.class);
			PendingIntent.getActivity(this, 0, i, 0);
			PendingIntent pi = PendingIntent.getActivity(this, 0, i, 0);
			String title = getString(R.string.notification_title);
			String content = getString(R.string.notification_state_locked);
			NotificationCompat.Builder nb = new NotificationCompat.Builder(this);
			nb.setSmallIcon(R.drawable.ic_launcher);
			nb.setContentTitle(title);
			nb.setContentText(content);
			nb.setWhen(System.currentTimeMillis());
			nb.setContentIntent(pi);
			nb.setOngoing(true);
			nb.setPriority(mNotificationPriority);
			startForeground(NOTIFICATION_ID, nb.build());
		} else if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.JELLY_BEAN_MR1) {
			// Hack for 4.2 and below to get system priority
			@SuppressWarnings("deprecation")
			Notification n = new Notification(0, null,
					System.currentTimeMillis());
			n.flags |= Notification.FLAG_NO_CLEAR;
			startForeground(NOTIFICATION_ID, n);
		}
	}

	/** Checks whether this service is in foreground (StackOverflow) */
	// private void checkForeground() {
	// ActivityManager am = (ActivityManager) this
	// .getSystemService(ACTIVITY_SERVICE);
	// List<RunningServiceInfo> l = am.getRunningServices(Integer.MAX_VALUE);
	// Iterator<RunningServiceInfo> i = l.iterator();
	// while (i.hasNext()) {
	// RunningServiceInfo rsi = (RunningServiceInfo) i.next();
	// if (rsi.service.getPackageName().equals(this.getPackageName())) {
	// if (rsi.foreground) {
	// Log.d(TAG, "Service is in foreground");
	// return;
	// }
	// }
	// }
	// Log.d(TAG, "Service is NOT in foreground");
	// }

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		Log.d(TAG, "onStartCommand");

		mExplicitStarted = true;
		loadPreferences();
		restart();

		return START_STICKY;
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		Log.w(TAG, "onDestroy");
		stopScheduler();
		mAM = null;
		mTrackedApps = null;
		stopForeground(true);
		unregisterReceiver(mScreenReceiver);
	}

	/**
	 * Loads or reloads the preferences at runtime and automatically adapts the
	 * service to match the new preferences.
	 */
	public void loadPreferences() {

		/*
		 * VARIABLES:
		 */
		SharedPreferences sp = getSharedPreferences(PREF_FILE_DEFAULT,
				MODE_PRIVATE);
		boolean defaultDelay = Boolean
				.parseBoolean(getString(R.string.pref_def_delay_status));
		boolean delayEnabled = sp.getBoolean(
				getString(R.string.pref_key_delay_status), defaultDelay);
		mDelayUnlockEnabled = delayEnabled;

		String delaySeconds = sp.getString(
				getString(R.string.pref_key_delay_time),
				getString(R.string.pref_def_delay_time));

		if (delaySeconds.length() == 0) {
			delaySeconds = "0";
		}

		mDelayUnlockRelockMillis = (Long.parseLong(delaySeconds) * 1000);

		boolean defaultScreenOffRelock = Boolean
				.parseBoolean(getString(R.string.pref_def_relock_after_screenoff));
		boolean relock = sp.getBoolean(
				getString(R.string.pref_key_relock_after_screenoff),
				defaultScreenOffRelock);
		mRelockAfterScreenOff = relock;

		boolean defaultShowNotification = Boolean
				.parseBoolean(getString(R.string.pref_def_show_notification));
		boolean showNotification = sp.getBoolean(
				getString(R.string.pref_key_show_notification),
				defaultShowNotification);
		mShowNotification = showNotification;

		boolean defaultTransparentNotification = Boolean
				.parseBoolean(getString(R.string.pref_def_transparent_notification));
		boolean transparentNotification = sp.getBoolean(
				getString(R.string.pref_key_transparent_notification),
				defaultTransparentNotification);
		mNotificationPriority = transparentNotification ? Notification.PRIORITY_MIN
				: Notification.PRIORITY_DEFAULT;

		mPassword = getPassword(this);
		loadTrackedApps();
	}

	/**
	 * Restarts everything in the service if the service was started manually.
	 * (Notification, Scheduler)
	 */
	public void restart() {
		if (mExplicitStarted) {
			startScheduler();
			startNotification();
		}
	}

	/**
	 * Starts (or restarts) the ScheduledExecutor with the correct peformance
	 * delay.
	 */
	private void startScheduler() {
		// Shutdown first if it's not running
		if (mScheduledExecutor != null) {
			mScheduledExecutor.shutdownNow();
			mScheduledExecutor = null;
		}
		String defaultDelay = getString(R.string.pref_val_perf_normal);
		SharedPreferences sp = getSharedPreferences(
				ObserverService.PREF_FILE_DEFAULT, Context.MODE_PRIVATE);
		String s = sp.getString(getString(R.string.pref_key_performance),
				defaultDelay);
		long delay = Long.parseLong(s);
		mScheduledExecutor = Executors.newSingleThreadScheduledExecutor();
		mScheduledExecutor.scheduleWithFixedDelay(new PackageMonitor(), 0,
				delay, TimeUnit.MILLISECONDS);
	}

	private void stopScheduler() {
		if (mScheduledExecutor != null) {
			mScheduledExecutor.shutdownNow();
			mScheduledExecutor = null;
		} else {
			Log.w(TAG, "Attempted to stop scheduler, but it was already null");
		}
	}

	class ScreenReceiver extends BroadcastReceiver {

		@Override
		public void onReceive(Context context, Intent intent) {
			if (intent.getAction().equals(Intent.ACTION_SCREEN_ON)) {
				Log.i("TAG", "Screen ON");
				mScreenOn = true;
				startScheduler();
			}
			if (intent.getAction().equals(Intent.ACTION_SCREEN_OFF)) {
				Log.i("TAG", "Screen OFF");
				mScreenOn = false;
				stopScheduler();
				if (mRelockAfterScreenOff) {
					lockAll();
				}

			}
		}
	};

	protected int mTest = 0;

	private class PackageMonitor implements Runnable {
		@Override
		public void run() {
			// long mBegin = System.nanoTime();
			// long mBegin = System.currentTimeMillis();

			ComponentName app = mAM.getRunningTasks(1).get(0).topActivity;
			String appName = app.getPackageName();
			String className = app.getClassName();

			boolean appChanged = !appName.equals(mLastApp);
			boolean classChanged = !className.equals(mLastClass);

			if (classChanged || appChanged) {
				Log.d(TAG, "" + appName + " " + className);
			}
			onObserve(appName, className);

			mLastClass = className;
			mLastApp = appName;

			// long mEnd = System.nanoTime();
			// long mEnd = System.currentTimeMillis();
			// if (classChanged || appChanged) {
			// Log.d(TAG, "" + mBegin);
			// Log.d(TAG, "" + mEnd);
			// Log.d(TAG, "" + (double) ((mEnd - mBegin)) + " ms");
			// // Log.d(TAG, "" + (double) ((mEnd - mBegin) / 1000000) +
			// // " ms");
			// Log.d(TAG, "----------------------");
			// }
		}

		/**
		 * Called each time the monitor has observed a package in the front. The
		 * package can be the same as the previous.
		 * 
		 * @param appName
		 * @param className
		 */
		private void onObserve(String appName, String className) {
			// Log.v(TAG, "Package: " + appName);
			if (className.equals(LOCKER_CLASS)) {
				return;
			}
			AppInfo app = getLockInfoByPackageName(appName);
			if (app != null) {
				if (app.locked) {
					app.className = className;
					Log.v(TAG,
							"Show locker for " + app.packageName
									+ app.hashCode());
					showLocker(app);
				}
			}
			// lock all other apps because they're not in front anymore
			lock(appName);
		}
	}

	/**
	 * Locks ALL apps (Useful when screen is turned off)
	 */
	private void lockAll() {
		for (AppInfo li : mTrackedApps) {
			if (li.locked == false) {
				Log.v(TAG, "relockAll() " + li.packageName);
				li.locked = true;

			}
		}
	}

	/**
	 * Locks all apps except the one matching the provided string.
	 * 
	 * @param appToExclude
	 *            The app that must NOT be locked.
	 */
	private void lock(String appToExclude) {
		// TODO timing
		for (AppInfo li : mTrackedApps) {
			if (!li.packageName.equals(appToExclude)) {
				if (li.locked == false) {
					Log.v(TAG, "relock() " + li.packageName);
					li.locked = true;
				}
			}
		}
	}

	/**
	 * Unlock a single application. Should be called by {@link LockActivity}
	 * 
	 * @param appName
	 */
	public void unlock(String appName) {
		Log.d(TAG, "doUnlock called");
		AppInfo li = getLockInfoByPackageName(appName);
		if (li != null) {
			if (li.locked == true) {
				Log.i(TAG, "Unlocked in list: " + li.packageName);
			} else {
				Log.w(TAG, "Tried to unlock " + li.hashCode()
						+ " but was not locked");
			}
			li.locked = false;
		} else {
			Log.w(TAG, "Not unlocked " + appName + ": not in list.");
		}
		if (mDelayUnlockEnabled) {
			stopScheduler();
			if (mDelayUnlockHandler == null) {
				mDelayUnlockHandler = new Handler();
			}
			mDelayUnlockHandler.removeCallbacksAndMessages(null);
			mDelayUnlockHandler.postDelayed(new Runnable() {

				@Override
				public void run() {
					startScheduler();
				}
			}, mDelayUnlockRelockMillis);
		}
	}

	private AppInfo getLockInfoByPackageName(String packageName) {
		if (mTrackedApps == null) {
			return null;
		}
		for (AppInfo li : mTrackedApps) {
			if (li.packageName.equals(packageName)) {
				return li;
			}
		}
		return null;
	}

	/**
	 * Display a {@link LockActivity} for this {@link AppInfo}.
	 * 
	 * @param lockInfo
	 *            The {@link AppInfo} to lock.
	 */
	@SuppressLint("NewApi")
	private void showLocker(AppInfo lockInfo) {
		if (mPassword.length() == 0) {
			Log.w(TAG, "Not showing lock for empty password:"
					+ lockInfo.packageName);
			return;
		}
		Log.d(TAG, "Starting locker for " + lockInfo.packageName);
		whatsappWorkaround(lockInfo); // TODO remove when whatsapp fix
		Intent intent = new Intent(ObserverService.this, LockActivity.class);
		intent.setAction(Intent.ACTION_VIEW);
		intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
		intent.putExtra(EXTRA_TARGET_PACKAGENAME, lockInfo.packageName);
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
			Bundle b = ActivityOptions.makeCustomAnimation(this,
					android.R.anim.fade_in, 0).toBundle();
			startActivity(intent, b);
		} else {
			startActivity(intent);

		}

	}

	/**
	 * BugFix for Whatsapp's lose focus.
	 * 
	 * @param li
	 */
	private void whatsappWorkaround(AppInfo li) {
		if (WHATSAPP_WORKAROUND) {
			if (li.className.equals("com.whatsapp.Conversation")) {
				try {
					Log.i(TAG, "Sleeping for whatsapp bug");
					Thread.sleep(WHATSAPP_WAIT_DELAY);
				} catch (InterruptedException e) {
					Log.e(TAG, "Interrupted while whatsapp workaround");
				}
			}
		}
	}

	/**
	 * Get password from SharedPreferences
	 * 
	 * @param c
	 * @return The password current password or an empty string.
	 */
	public static final String getPassword(Context c) {
		return c.getSharedPreferences(PREF_FILE_DEFAULT, MODE_PRIVATE)
				.getString(c.getString(R.string.pref_key_passwd), "");
	}

	/**
	 * Change the lock password and write it to disk
	 * 
	 * @param c
	 * @param password
	 * @return True if success, false on failure
	 */
	public static final boolean setPassword(Context c, String password) {
		SharedPreferences.Editor editor = c.getSharedPreferences(
				PREF_FILE_DEFAULT, MODE_PRIVATE).edit();
		editor.putString(c.getString(R.string.pref_key_passwd), password);
		return editor.commit();
	}

	public static final String getMessage(Context c) {
		return c.getSharedPreferences(PREF_FILE_DEFAULT, MODE_PRIVATE)
				.getString(c.getString(R.string.pref_key_lock_message),
						c.getString(R.string.locker_footer_default));
	}

	public static final boolean setMessage(Context c, String value) {
		SharedPreferences.Editor editor = c.getSharedPreferences(
				PREF_FILE_DEFAULT, MODE_PRIVATE).edit();
		editor.putString(c.getString(R.string.pref_key_lock_message), value);
		boolean commited = editor.commit();
		return commited;
	}

	/**
	 * 
	 * @param context
	 * @return A {@link Set} that is safe to edit and use.
	 */
	public static final Set<String> getTrackedApps(Context c) {
		SharedPreferences sp = c.getSharedPreferences(PREF_FILE_APPS,
				Context.MODE_PRIVATE);
		Set<String> apps = new HashSet<String>(sp.getAll().keySet());
		return apps;
	}

	public final void loadTrackedApps() {
		Set<String> apps = getTrackedApps(this);
		mTrackedApps = new HashSet<AppInfo>();
		for (String s : apps) {
			mTrackedApps.add(new AppInfo(s));
		}

	}

	/**
	 * Tracks or untracks an app
	 * 
	 * @param packageNames
	 * @param shouldTrack
	 *            True if the new state will be tracking, false if not
	 */
	public final void setTracking(boolean shouldTrack, String... packageNames) {
		SharedPreferences.Editor editor = getSharedPreferences(PREF_FILE_APPS,
				Context.MODE_PRIVATE).edit();
		for (String packageName : packageNames) {
			if (shouldTrack) {
				editor.putBoolean(packageName, true);
			} else {
				editor.remove(packageName);
			}
		}
		boolean commited = editor.commit();
		if (!commited) {
			Log.w(TAG, "Not commited!");
		}
		loadTrackedApps();
	}
}
