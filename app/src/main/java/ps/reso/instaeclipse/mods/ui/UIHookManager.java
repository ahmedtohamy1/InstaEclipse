package ps.reso.instaeclipse.mods.ui;

import static ps.reso.instaeclipse.mods.ghost.ui.GhostEmojiManager.addGhostEmojiNextToInbox;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.Manifest;
import android.content.pm.PackageManager;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.ImageView;
import android.widget.Toast;

import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.Queue;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import ps.reso.instaeclipse.Xposed.Module;
import ps.reso.instaeclipse.mods.devops.config.ConfigManager;
import ps.reso.instaeclipse.mods.ui.utils.BottomSheetHookUtil;
import ps.reso.instaeclipse.mods.ui.utils.VibrationUtil;
import ps.reso.instaeclipse.utils.dialog.DialogUtils;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.ghost.GhostModeUtils;
import ps.reso.instaeclipse.utils.toast.CustomToast;

public class UIHookManager {
    private static final int MIN_FEED_IMAGE_AREA_PX = 40000;
    private static final int MIN_POST_CONTAINER_IMAGE_AREA_PX = 160000;
    private static final int STORAGE_PERMISSION_REQUEST_CODE = 4019;
    private static final String FEED_DOWNLOAD_TAG = "instaeclipse_feed_download_button";
    private static final String FEED_DOWNLOAD_SCAN_TAG = "instaeclipse_feed_download_scan_listener";

    @SuppressLint("StaticFieldLeak")
    private static Activity currentActivity;

    public static Activity getCurrentActivity() {
        return currentActivity;
    }

    private static boolean isAnyGhostOptionEnabled() {
        return GhostModeUtils.isGhostModeActive();
    }

    public static void setupHooks(Activity activity) {
        // Hook Search Tab (open InstaEclipse Settings)
        String[] possibleSearch = {"search_tab", "action_bar_end_action_buttons"};

        for (String id : possibleSearch) {
            @SuppressLint("DiscouragedApi")
            int viewId = activity.getResources().getIdentifier(id, "id", activity.getPackageName());
            View view = activity.findViewById(viewId);

            if (view != null) {
                processSearchView(activity, view, id);
            } else {
                // VIEW NOT FOUND YET: Wait for the layout to change and try again
                final View decorView = activity.getWindow().getDecorView();
                decorView.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        View lateView = activity.findViewById(viewId);
                        if (lateView != null) {
                            processSearchView(activity, lateView, id);
                            // Remove listener so we don't keep calling this unnecessarily
                            decorView.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                        }
                    }
                });
            }
        }

        // Hook Inbox Button (toggle Ghost Quick Options)
        String[] possibleIds = {"action_bar_inbox_button", "direct_tab"};

        for (String id : possibleIds) {
            @SuppressLint("DiscouragedApi") int viewId = activity.getResources().getIdentifier(id, "id", activity.getPackageName());
            View view = activity.findViewById(viewId);
            if (view != null) {
                hookLongPress(activity, id, v -> {
                    GhostModeUtils.toggleSelectedGhostOptions(activity);
                    VibrationUtil.vibrate(activity);
                    return true;
                });
                break;
            }
        }

        addGhostEmojiNextToInbox(activity, GhostModeUtils.isGhostModeActive());

        // Mark messages (DM) as seen by holding on gallery button
        hookLongPress(activity, "row_thread_composer_button_gallery", v -> {
            VibrationUtil.vibrate(activity);

            if (!FeatureFlags.isGhostSeen) {
                return true;
            }

            FeatureFlags.isGhostSeen = false;

            activity.getWindow().getDecorView().post(() -> {
                try {
                    // Look for the exact message list view by ID
                    @SuppressLint("DiscouragedApi") int messageListId = activity.getResources().getIdentifier("message_list", "id", activity.getPackageName());
                    View view = activity.findViewById(messageListId);

                    if (view instanceof ViewGroup messageList) {

                        // Try scrolling via translation if standard scroll methods don't exist
                        messageList.scrollBy(0, -100); // scroll up

                        new Handler(Looper.getMainLooper()).postDelayed(() -> {
                            messageList.scrollBy(0, 100); // scroll back down

                            FeatureFlags.isGhostSeen = true;
                            Toast.makeText(activity, "✅ Message was marked as read", Toast.LENGTH_SHORT).show();

                        }, 300);


                    } else {
                        XposedBridge.log("⚠️ message_list not a ViewGroup or not found — fallback to reset flag");

                        new Handler(Looper.getMainLooper()).postDelayed(() -> FeatureFlags.isGhostSeen = true, 300);
                    }
                } catch (Exception e) {
                    XposedBridge.log("❌ Exception in scroll logic: " + Log.getStackTraceString(e));
                }
            });

            return true;
        });

        injectFeedDownloadButtons(activity);
        ensureFeedDownloadInjectionOnLayout(activity);

    }

    private static void injectFeedDownloadButtons(Activity activity) {
        final String[] possibleActionButtons = {
                "row_feed_button_comment",
                "feed_comment_button",
                "row_feed_button_like",
                "feed_like_button"
        };

        for (String actionId : possibleActionButtons) {
            @SuppressLint("DiscouragedApi")
            int viewId = activity.getResources().getIdentifier(actionId, "id", activity.getPackageName());
            if (viewId == 0) {
                continue;
            }

            View actionView = activity.findViewById(viewId);
            if (actionView == null) {
                continue;
            }

            if (!(actionView.getParent() instanceof ViewGroup actionBar)) {
                continue;
            }

            if (actionBar.findViewWithTag(FEED_DOWNLOAD_TAG) != null) {
                continue;
            }

            ImageView downloadButton = new ImageView(activity);
            downloadButton.setTag(FEED_DOWNLOAD_TAG);
            downloadButton.setImageResource(android.R.drawable.stat_sys_download_done);
            downloadButton.setContentDescription("Download image");
            int size = dpToPx(activity, 24);
            int padding = dpToPx(activity, 8);
            ViewGroup.MarginLayoutParams params = new ViewGroup.MarginLayoutParams(size, size);
            params.leftMargin = dpToPx(activity, 8);
            downloadButton.setLayoutParams(params);
            downloadButton.setPadding(padding / 2, padding / 2, padding / 2, padding / 2);
            downloadButton.setClickable(true);
            downloadButton.setFocusable(true);

            downloadButton.setOnClickListener(v -> {
                if (!hasStorageAccess(activity)) {
                    requestLegacyStoragePermission(activity);
                    Toast.makeText(activity, "Grant storage permission, then tap download again", Toast.LENGTH_SHORT).show();
                    return;
                }

                Drawable drawable = findFeedImageDrawableFromActionBar(actionBar);
                if (drawable == null) {
                    Toast.makeText(activity, "Unable to find feed image", Toast.LENGTH_SHORT).show();
                    return;
                }

                Bitmap bitmap = drawableToBitmap(drawable);
                if (bitmap == null) {
                    Toast.makeText(activity, "Unable to capture image", Toast.LENGTH_SHORT).show();
                    return;
                }

                boolean saved = saveBitmapToGallery(activity, bitmap);
                Toast.makeText(activity, saved ? "Image downloaded" : "Download failed", Toast.LENGTH_SHORT).show();
                if (saved) {
                    VibrationUtil.vibrate(activity);
                }
            });

            actionBar.addView(downloadButton);
        }

        injectButtonsByHeuristicScan(activity);
    }

    private static void ensureFeedDownloadInjectionOnLayout(Activity activity) {
        final View root = activity.getWindow().getDecorView();
        if (root.getTag() != null && FEED_DOWNLOAD_SCAN_TAG.equals(root.getTag().toString())) {
            return;
        }

        root.setTag(FEED_DOWNLOAD_SCAN_TAG);
        root.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                try {
                    injectButtonsByHeuristicScan(activity);
                } catch (Exception ignored) {
                }
            }
        });
    }

    private static void injectButtonsByHeuristicScan(Activity activity) {
        View root = activity.getWindow().getDecorView();
        if (!(root instanceof ViewGroup rootGroup)) {
            return;
        }

        Queue<View> queue = new ArrayDeque<>();
        queue.add(rootGroup);

        while (!queue.isEmpty()) {
            View current = queue.poll();
            if (current instanceof ViewGroup group) {
                if (looksLikeFeedActionBar(group) && group.findViewWithTag(FEED_DOWNLOAD_TAG) == null) {
                    addDownloadButtonToActionBar(activity, group);
                }
                for (int i = 0; i < group.getChildCount(); i++) {
                    queue.add(group.getChildAt(i));
                }
            }
        }
    }

    private static boolean looksLikeFeedActionBar(ViewGroup group) {
        boolean hasLike = false;
        boolean hasComment = false;

        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            CharSequence desc = child.getContentDescription();
            if (desc == null) {
                continue;
            }
            String text = desc.toString().toLowerCase();
            if (text.contains("like")) {
                hasLike = true;
            }
            if (text.contains("comment")) {
                hasComment = true;
            }
        }

        return hasLike && hasComment;
    }

    private static void addDownloadButtonToActionBar(Activity activity, ViewGroup actionBar) {
        ImageView downloadButton = new ImageView(activity);
        downloadButton.setTag(FEED_DOWNLOAD_TAG);
        downloadButton.setImageResource(android.R.drawable.stat_sys_download_done);
        downloadButton.setContentDescription("Download image");
        int size = dpToPx(activity, 24);
        int padding = dpToPx(activity, 8);
        ViewGroup.MarginLayoutParams params = new ViewGroup.MarginLayoutParams(size, size);
        params.leftMargin = dpToPx(activity, 8);
        downloadButton.setLayoutParams(params);
        downloadButton.setPadding(padding / 2, padding / 2, padding / 2, padding / 2);
        downloadButton.setClickable(true);
        downloadButton.setFocusable(true);
        downloadButton.setAlpha(0.95f);

        downloadButton.setOnClickListener(v -> {
            if (!hasStorageAccess(activity)) {
                requestLegacyStoragePermission(activity);
                Toast.makeText(activity, "Grant storage permission, then tap download again", Toast.LENGTH_SHORT).show();
                return;
            }

            Drawable drawable = findFeedImageDrawableFromActionBar(actionBar);
            if (drawable == null) {
                Toast.makeText(activity, "Unable to find feed image", Toast.LENGTH_SHORT).show();
                return;
            }

            Bitmap bitmap = drawableToBitmap(drawable);
            if (bitmap == null) {
                Toast.makeText(activity, "Unable to capture image", Toast.LENGTH_SHORT).show();
                return;
            }

            boolean saved = saveBitmapToGallery(activity, bitmap);
            Toast.makeText(activity, saved ? "Image downloaded" : "Download failed", Toast.LENGTH_SHORT).show();
            if (saved) {
                VibrationUtil.vibrate(activity);
            }
        });

        actionBar.addView(downloadButton);
    }

    private static int dpToPx(Context context, int dp) {
        float density = context.getResources().getDisplayMetrics().density;
        return (int) (dp * density);
    }

    private static boolean hasStorageAccess(Activity activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return true;
        }
        return activity.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private static void requestLegacyStoragePermission(Activity activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return;
        }
        activity.requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, STORAGE_PERMISSION_REQUEST_CODE);
    }

    private static Drawable findFeedImageDrawableFromActionBar(View actionBar) {
        ViewGroup postContainer = findLikelyPostContainer(actionBar);
        if (postContainer == null) {
            return null;
        }

        return findLargestImageDrawable(postContainer, MIN_FEED_IMAGE_AREA_PX);
    }

    private static ViewGroup findLikelyPostContainer(View startView) {
        View current = startView;
        int maxDepth = 12;
        while (current != null && maxDepth-- > 0) {
            if (current instanceof ViewGroup candidate) {
                Drawable preview = findLargestImageDrawable(candidate, MIN_POST_CONTAINER_IMAGE_AREA_PX);
                if (preview != null) {
                    return candidate;
                }
            }
            if (!(current.getParent() instanceof View)) {
                break;
            }
            current = (View) current.getParent();
        }
        return null;
    }

    private static Drawable findLargestImageDrawable(ViewGroup rootGroup, int minAreaPx) {
        if (rootGroup == null) {
            return null;
        }

        Queue<View> queue = new ArrayDeque<>();
        queue.add(rootGroup);
        Drawable bestDrawable = null;
        int bestArea = 0;

        while (!queue.isEmpty()) {
            View current = queue.poll();
            if (current instanceof ImageView imageView) {
                Drawable drawable = imageView.getDrawable();
                int area = imageView.getWidth() * imageView.getHeight();
                if (drawable != null && area > minAreaPx && area > bestArea) {
                    bestDrawable = drawable;
                    bestArea = area;
                }
            }

            if (current instanceof ViewGroup group) {
                for (int i = 0; i < group.getChildCount(); i++) {
                    queue.add(group.getChildAt(i));
                }
            }
        }

        return bestDrawable;
    }

    private static Bitmap drawableToBitmap(Drawable drawable) {
        if (drawable instanceof BitmapDrawable bitmapDrawable && bitmapDrawable.getBitmap() != null) {
            return bitmapDrawable.getBitmap();
        }

        int width = Math.max(drawable.getIntrinsicWidth(), 1);
        int height = Math.max(drawable.getIntrinsicHeight(), 1);
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
        drawable.draw(canvas);
        return bitmap;
    }

    private static boolean saveBitmapToGallery(Context context, Bitmap bitmap) {
        String fileName = "instaeclipse_" + System.currentTimeMillis() + ".jpg";
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/InstaEclipse");
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
        }

        Uri uri = context.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) {
            return false;
        }

        try (OutputStream stream = context.getContentResolver().openOutputStream(uri)) {
            if (stream == null) {
                context.getContentResolver().delete(uri, null, null);
                return false;
            }

            boolean compressed = bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream);
            if (!compressed) {
                context.getContentResolver().delete(uri, null, null);
                return false;
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues pendingValues = new ContentValues();
                pendingValues.put(MediaStore.Images.Media.IS_PENDING, 0);
                context.getContentResolver().update(uri, pendingValues, null, null);
            }
            return true;
        } catch (Exception e) {
            context.getContentResolver().delete(uri, null, null);
            XposedBridge.log("(InstaEclipse | FeedDownload): Failed to save image: " + e.getMessage());
            return false;
        }
    }

    // Hook long press method
    private static void hookLongPress(Activity activity, String viewName, View.OnLongClickListener listener) {
        try {
            @SuppressLint("DiscouragedApi") int viewId = activity.getResources().getIdentifier(viewName, "id", activity.getPackageName());
            View view = activity.findViewById(viewId);

            if (view != null) {
                view.setOnLongClickListener(listener);
            }
        } catch (Exception ignored) {
        }
    }

    public void mainActivity(ClassLoader classLoader) {
        // Hook onCreate of Instagram Main
        XposedHelpers.findAndHookMethod("com.instagram.mainactivity.InstagramMainActivity", classLoader, "onCreate", android.os.Bundle.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                final Activity activity = (Activity) param.thisObject;
                currentActivity = activity;
                activity.runOnUiThread(() -> {
                    try {
                        setupHooks(activity);
                        addGhostEmojiNextToInbox(activity, isAnyGhostOptionEnabled());
                        if (!FeatureFlags.showFeatureToasts || CustomToast.toastShown) return;
                        CustomToast.toastShown = true;

                        new Handler(Looper.getMainLooper()).postDelayed(() -> {
                            StringBuilder sb = new StringBuilder("InstaEclipse Loaded 🎯\n");
                            for (Map.Entry<String, Boolean> entry : FeatureStatusTracker.getStatus().entrySet()) {
                                sb.append(entry.getValue() ? "✅ " : "❌ ").append(entry.getKey()).append("\n");
                            }
                            CustomToast.showCustomToast(activity.getApplicationContext(), sb.toString().trim());
                        }, 1000);
                    } catch (Exception ignored) {

                    }
                });
            }
        });

        // Hook onResume - Instagram Main
        XposedHelpers.findAndHookMethod("com.instagram.mainactivity.InstagramMainActivity", classLoader, "onResume", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                final Activity activity = (Activity) param.thisObject;
                currentActivity = activity;
                activity.runOnUiThread(() -> {
                    try {
                        setupHooks(activity);
                        addGhostEmojiNextToInbox(activity, isAnyGhostOptionEnabled());

                        if (FeatureFlags.isImportingConfig) {
                            // De-bounce: flip it off first so it won't re-trigger on next onResume
                            FeatureFlags.isImportingConfig = false;
                            ConfigManager.importConfigFromClipboard(activity);
                        }
                    } catch (Exception ignored) {
                    }
                });
            }
        });


        // Hook getBottomSheetNavigator - Instagram Main
        BottomSheetHookUtil.hookBottomSheetNavigator(Module.dexKitBridge);

        // Hook onResume - Model
        XposedHelpers.findAndHookMethod("com.instagram.modal.ModalActivity", classLoader, "onResume", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Activity activity = (Activity) param.thisObject;
                if (activity != null) {
                    activity.runOnUiThread(() -> {
                        try {
                            setupHooks(activity);
                        } catch (Exception ignored) {
                        }
                    });
                }
            }
        });
    }

    private static void applySearchHook(Activity activity, View v) {
        v.setOnLongClickListener(view -> {
            DialogUtils.showEclipseOptionsDialog(activity);
            VibrationUtil.vibrate(activity);
            return true;
        });
    }

    private static void processSearchView(Activity activity, View view, String id) {
        if (id.equals("action_bar_end_action_buttons") && view instanceof ViewGroup container) {
            for (int i = 0; i < container.getChildCount(); i++) {
                View child = container.getChildAt(i);
                CharSequence description = child.getContentDescription();
                if (description != null && description.toString().toLowerCase().contains("search")) {
                    applySearchHook(activity, child);
                }
            }
        } else {
            applySearchHook(activity, view);
        }
    }

}
