package com.tianqianguai.gramsieve.module;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.ApplicationInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.os.Bundle;
import android.util.Base64;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Menu;
import android.view.MenuItem;
import android.view.SubMenu;
import android.view.ViewParent;
import android.widget.ArrayAdapter;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.PopupWindow;

import androidx.recyclerview.widget.RecyclerView;

import com.tianqianguai.gramsieve.R;
import com.tianqianguai.gramsieve.config.ChatReadPositionStore;
import com.tianqianguai.gramsieve.config.ConfigContentProvider;
import com.tianqianguai.gramsieve.config.ConfigUpdateReceiver;
import com.tianqianguai.gramsieve.config.DiagnosticLogStore;
import com.tianqianguai.gramsieve.config.AntiRecallConfigStore;
import com.tianqianguai.gramsieve.config.ModuleLogger;
import com.tianqianguai.gramsieve.config.ModuleConfigStore;
import com.tianqianguai.gramsieve.config.XposedConfigProvider;
import com.tianqianguai.gramsieve.core.FilterConfig;
import com.tianqianguai.gramsieve.core.FilterDecision;
import com.tianqianguai.gramsieve.core.FilterEngine;
import com.tianqianguai.gramsieve.core.MessageRuleFactory;
import com.tianqianguai.gramsieve.core.MessageSnapshot;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

final class TelegramHookInstaller {
    private static final String TAG = "GramSieve";
    private static final String MODULE_PACKAGE = "com.tianqianguai.gramsieve";
    private static final String CONFIG_MODE_GLOBAL = "global";
    private static final String CONFIG_MODE_CHAT = "chat";
    private static final int MENU_ID_CHAT = 0x47530011;
    private static final int MENU_ID_GLOBAL = 0x47530012;
    private static final int MENU_ID_BLOCK_MESSAGE = 0x47530013;
    private static final int MENU_ID_SMART_BLOCK = 0x4753001C;
    private static final int MENU_ID_SCROLL_TOP = 0x47530014;
    private static final int MENU_ID_SELECT_ALL = 0x47530015;
    private static final int MENU_ID_MARK_MESSAGE = 0x47530016;
    private static final int MENU_ID_JUMP_TO_MARK = 0x47530017;
    private static final int MENU_ID_ANTI_RECALL = 0x47530018;
    private static final int MENU_ID_EDIT_HISTORY = 0x47530019;
    private static final int SETTINGS_ROW_GRAMSIEVE = 0x4753001A;
    private static final int MENU_ID_CLEANUP_MODE = 0x4753001B;
    private static final int MENU_ID_ADD_TO_FOLDER = 0x4753001D;
    private static final int SETTINGS_ROW_COLOR_START = 0xFF2AABEE;
    private static final int SETTINGS_ROW_COLOR_END = 0xFF229ED9;
    private ViewGroup downloadPageFragmentView = null;
    private MessageCache messageCache;
    private MediaCache mediaCache;
    private MediaPrefetcher mediaPrefetcher;
    private final DownloadCancellationRegistry downloadCancellationRegistry =
            new DownloadCancellationRegistry();
    private ReliableVideoDownloadManager reliableVideoDownloads;
    private BackgroundMessageLoader backgroundMessageLoader;
    private RecallDetector recallDetector;
    private AntiRecallConfigStore antiRecallConfigStore;
    private LocalDialogDeleteStore localDialogDeleteStore;
    private TelegramDialogDatabasePruner dialogDatabasePruner;
    private final Set<LocalDialogDeleteStore.HiddenDialog> locallyHiddenDialogs =
            Collections.synchronizedSet(new HashSet<>());
    private static final int SCROLL_JUMP_THRESHOLD = 50;
    private static final long CLEANUP_MODE_DURATION_MS = SelfDeleteTracker.DEFAULT_CLEANUP_MODE_MS;

    private final XposedModule module;
    private XposedConfigProvider configProvider;
    // Dialog list features (Build #14): last DialogsActivity, held weakly
    // only so the classify bar can trigger a list refresh on filter change.
    private java.lang.ref.WeakReference<Object> lastDialogsActivityRef;
    private volatile long lastClassifyBarSyncMs = 0L;
    private volatile long lastClassifyBarInstallAttemptMs = 0L;
    private final FilterEngine filterEngine = new FilterEngine();
    private final DecisionCache decisionCache = new DecisionCache();
    private final AtomicInteger bindingProbeBudget = new AtomicInteger(12);
    private final AtomicInteger hookEntryBudget = new AtomicInteger(24);
    private final AtomicInteger decisionProbeBudget = new AtomicInteger(12);
    private final AtomicInteger refreshProbeBudget = new AtomicInteger(12);
    private final AtomicInteger readMarkProbeBudget = new AtomicInteger(16);
    private final Map<String, Long> recentDiagnosticKeys = new LinkedHashMap<String, Long>(128, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
            return size() > 128;
        }
    };
    private final Map<String, Long> recentReadMarkKeys = new LinkedHashMap<String, Long>(512, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
            return size() > 512;
        }
    };
    private boolean installed;
    private boolean persistentDiagnosticsUnavailable;
    private volatile long trackedDialogId;
    private volatile int lastTopmostMessageId;
    private volatile boolean readPositionDirty;
    private volatile boolean jumpDetected;
    private volatile boolean settingsListRowLogged;

    TelegramHookInstaller(XposedModule module) {
        this.module = module;
    }

    synchronized void install(ClassLoader classLoader, ApplicationInfo applicationInfo) {
        if (installed) {
            return;
        }
        if (configProvider == null) {
            configProvider = new XposedConfigProvider(
                    MODULE_PACKAGE,
                    () -> module.getRemotePreferences(ModuleConfigStore.PREFS_NAME)
            );
        }
        initAntiRecall(classLoader);
        logRemoteCapabilities();
        logTelegramVersion(classLoader, applicationInfo);
        hookPushNotificationTriggers(classLoader);
        hookTaggedViewMeasure();
        hookReliableVideoDownloads(classLoader);
        hookChatMessageCell(classLoader);
        hookRecyclerViewBinding(classLoader);
        hookChatActivityAdapter(classLoader);
        hookChatActivityMenu(classLoader);
        hookChatActivityResume(classLoader);
        hookChatActivityPause(classLoader);
        hookScrollToLastMessage(classLoader);
        hookMessageContextMenu(classLoader);
        hookEnhancements(classLoader);
        hookSettingsActivityMenu(classLoader);
        hookProfileSettingsMenu(classLoader);
        hookDownloadActivityMenu(classLoader);
        hookDialogDeletionDiagnostics(classLoader);
        hookOnItemClickDiagnostic(classLoader);
        installed = true;
        info("Installed Telegram hooks");
    }

    private volatile ClassLoader savedClassLoader;

    private void initAntiRecall(ClassLoader classLoader) {
        this.savedClassLoader = classLoader;
        try {
            Context context = resolveHostApplication();
            if (context == null) {
                info("Anti-recall: host application context not available, deferring");
                return;
            }
            doInitAntiRecall(context, classLoader);
        } catch (Throwable throwable) {
            error("Failed to initialize anti-recall components", throwable);
        }
    }

    private void initAntiRecallDeferred() {
        if (backgroundMessageLoader != null) {
            return;
        }
        try {
            Context context = resolveHostApplication();
            if (context == null) {
                return;
            }
            doInitAntiRecall(context, savedClassLoader);
        } catch (Throwable throwable) {
            error("Failed to deferred-initialize anti-recall", throwable);
        }
    }

    private void initAntiRecallFromChat(Object chatActivity) {
        if (backgroundMessageLoader != null) {
            return;
        }
        try {
            Context context = resolveContextFromActivity(chatActivity);
            initAntiRecallFromContext(context, "chat");
        } catch (Throwable throwable) {
            error("Failed to initialize anti-recall from chat", throwable);
        }
    }

    private synchronized void initAntiRecallFromContext(Context context, String source) {
        if (backgroundMessageLoader != null) {
            return;
        }
        if (context == null) {
            info("Anti-recall: " + source + " context is null");
            return;
        }
        Context appContext = context.getApplicationContext() != null
                ? context.getApplicationContext()
                : context;
        info("Anti-recall: initializing from " + source + " context");
        doInitAntiRecall(appContext, savedClassLoader);
    }

    private void doInitAntiRecall(Context context, ClassLoader classLoader) {
        ModuleLogger.init(context);
        antiRecallConfigStore = new AntiRecallConfigStore(context);
        localDialogDeleteStore = new LocalDialogDeleteStore(context);
        dialogDatabasePruner = new TelegramDialogDatabasePruner(context, new TelegramDialogDatabasePruner.Logger() {
            @Override
            public void info(String message) {
                TelegramHookInstaller.this.info(message);
            }

            @Override
            public void error(String message, Throwable throwable) {
                TelegramHookInstaller.this.error(message, throwable);
            }
        });
        locallyHiddenDialogs.clear();
        Set<LocalDialogDeleteStore.HiddenDialog> hiddenDialogs =
                localDialogDeleteStore.hiddenDialogs(resolveSelectedTelegramAccount(classLoader));
        locallyHiddenDialogs.addAll(hiddenDialogs);
        pruneKnownHiddenDialogs(hiddenDialogs);
        MessageDatabaseHelper databaseHelper = new MessageDatabaseHelper(context);
        messageCache = new MessageCache(new SerializedMessageStore(databaseHelper));
        mediaCache = new MediaCache(context);
        mediaPrefetcher = new MediaPrefetcher(messageCache, mediaCache, downloadCancellationRegistry);
        backgroundMessageLoader = new BackgroundMessageLoader(messageCache, antiRecallConfigStore);
        recallDetector = new RecallDetector(messageCache, backgroundMessageLoader, mediaPrefetcher);
        backgroundMessageLoader.setLoadedMessagesConsumer(recallDetector::cacheBackgroundMessages);
        if (classLoader != null) {
            mediaPrefetcher.setTelegramClassLoader(classLoader);
            recallDetector.install(classLoader, module);
            backgroundMessageLoader.setTelegramClassLoader(classLoader);
        }
        info("Anti-recall components initialized");
    }

    private void hookPushNotificationTriggers(ClassLoader classLoader) {
        hookPushListenerController(classLoader);
        hookConnectionsManagerPushFallback(classLoader);
    }

    private void hookPushListenerController(ClassLoader classLoader) {
        try {
            Class<?> pushControllerClass = classLoader.loadClass("org.telegram.messenger.PushListenerController");
            boolean hooked = false;
            for (Method method : pushControllerClass.getDeclaredMethods()) {
                if (!"processRemoteMessage".equals(method.getName())) {
                    continue;
                }
                hook(method, chain -> {
                    triggerImmediateBackgroundLoad(
                            "PushListenerController.processRemoteMessage" + describeHookArgs(chain.getArgs())
                    );
                    return chain.proceed();
                });
                hooked = true;
            }
            if (hooked) {
                info("Anti-recall: hooked PushListenerController.processRemoteMessage for push-triggered loading");
            } else {
                info("Anti-recall: PushListenerController.processRemoteMessage not found");
            }
        } catch (ClassNotFoundException throwable) {
            info("Anti-recall: PushListenerController unavailable");
        } catch (Throwable throwable) {
            error("Anti-recall: failed to hook PushListenerController", throwable);
        }
    }

    private void hookConnectionsManagerPushFallback(ClassLoader classLoader) {
        try {
            Class<?> connectionsManagerClass = classLoader.loadClass("org.telegram.tgnet.ConnectionsManager");
            boolean hooked = false;
            for (Method method : connectionsManagerClass.getDeclaredMethods()) {
                if (!"onInternalPushReceived".equals(method.getName())) {
                    continue;
                }
                hook(method, chain -> {
                    triggerImmediateBackgroundLoad(
                            "ConnectionsManager.onInternalPushReceived" + describeHookArgs(chain.getArgs())
                    );
                    return chain.proceed();
                });
                hooked = true;
            }
            if (hooked) {
                info("Anti-recall: hooked ConnectionsManager.onInternalPushReceived fallback");
            } else {
                info("Anti-recall: ConnectionsManager.onInternalPushReceived not found");
            }
        } catch (ClassNotFoundException throwable) {
            info("Anti-recall: ConnectionsManager unavailable for push fallback");
        } catch (Throwable throwable) {
            error("Anti-recall: failed to hook ConnectionsManager push fallback", throwable);
        }
    }

    private void triggerImmediateBackgroundLoad(String reason) {
        try {
            initAntiRecallDeferred();
            BackgroundMessageLoader loader = backgroundMessageLoader;
            if (loader == null) {
                info("Anti-recall: push-triggered load skipped, loader unavailable reason=" + reason);
                return;
            }
            loader.triggerImmediateLoad(reason);
        } catch (Throwable throwable) {
            error("Anti-recall: push-triggered load failed reason=" + reason, throwable);
        }
    }

    private String describeHookArgs(List<Object> args) {
        if (args == null || args.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder(" args=[");
        for (int i = 0; i < args.size(); i++) {
            if (i > 0) {
                builder.append(',');
            }
            Object arg = args.get(i);
            if (arg instanceof CharSequence) {
                builder.append("strLen=").append(((CharSequence) arg).length());
            } else {
                builder.append(String.valueOf(arg));
            }
        }
        return builder.append(']').toString();
    }

    private Context resolveHostApplication() {
        try {
            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            java.lang.reflect.Method currentApplication = activityThreadClass.getDeclaredMethod("currentApplication");
            currentApplication.setAccessible(true);
            Object app = currentApplication.invoke(null);
            return app instanceof Context ? (Context) app : null;
        } catch (Throwable throwable) {
            info("Anti-recall: ActivityThread.currentApplication() unavailable");
            return null;
        }
    }

    private void hookTaggedViewMeasure() {
        try {
            Method measure = Reflect.method(View.class, "measure", int.class, int.class);
            hook(measure, chain -> {
                Object result = chain.proceed();
                Object view = chain.getThisObject();
                if (view instanceof View) {
                    UiMutation.overrideMeasuredHeight((View) view, null);
                }
                return result;
            });
            info("Hooked View.measure for tagged hidden rows");
        } catch (Throwable throwable) {
            error("Failed to hook View.measure", throwable);
        }
    }

    private void logRemoteCapabilities() {
        try {
            String[] remoteFiles = module.listRemoteFiles();
            info(
                    "Remote caps properties=" + module.getFrameworkProperties()
                            + " files=" + (remoteFiles == null ? "null" : Arrays.toString(remoteFiles))
            );
        } catch (Throwable throwable) {
            error("Remote capability probe failed", throwable);
        }
    }

    private void hookChatMessageCell(ClassLoader classLoader) {
        try {
            Class<?> messageObjectClass = classLoader.loadClass("org.telegram.messenger.MessageObject");
            Class<?> groupedMessagesClass = classLoader.loadClass("org.telegram.messenger.MessageObject$GroupedMessages");
            Class<?> cellClass = classLoader.loadClass("org.telegram.ui.Cells.ChatMessageCell");
            boolean hooked = false;
            hooked |= tryHookMessageMethod(
                    cellClass,
                    "setMessageObject",
                    new Class<?>[]{messageObjectClass, groupedMessagesClass, boolean.class, boolean.class}
            );
            hooked |= tryHookMessageMethod(
                    cellClass,
                    "setMessageObject",
                    new Class<?>[]{messageObjectClass, groupedMessagesClass, boolean.class, boolean.class, boolean.class}
            );
            hooked |= tryHookMessageMethod(
                    cellClass,
                    "setMessageObject",
                    new Class<?>[]{messageObjectClass, groupedMessagesClass, boolean.class, boolean.class, boolean.class, boolean.class}
            );
            hooked |= tryHookMessageMethod(
                    cellClass,
                    "setMessageObjectInternal",
                    new Class<?>[]{messageObjectClass}
            );
            hooked |= tryHookMessageMethod(
                    cellClass,
                    "setMessageContent",
                    new Class<?>[]{messageObjectClass, groupedMessagesClass, boolean.class, boolean.class, boolean.class, boolean.class}
            );
            hooked |= tryHookCellLifecycleMethod(
                    cellClass,
                    "onLayout",
                    new Class<?>[]{boolean.class, int.class, int.class, int.class, int.class}
            );
            hooked |= tryHookCellLifecycleMethod(
                    cellClass,
                    "onAttachedToWindow",
                    new Class<?>[0]
            );
            hooked |= tryHookCellMeasureMethod(
                    cellClass,
                    "onMeasure",
                    new Class<?>[]{int.class, int.class}
            );
            if (!hooked) {
                throw new IllegalStateException("No ChatMessageCell hook points were registered");
            }
        } catch (Throwable throwable) {
            error("Failed to hook ChatMessageCell", throwable);
        }
    }

    private void hookReliableVideoDownloads(ClassLoader classLoader) {
        reliableVideoDownloads = new ReliableVideoDownloadManager(downloadCancellationRegistry);
        reliableVideoDownloads.setClassLoader(classLoader);
        hookReliableDownloadButton(classLoader);
        hookReliableDownloadMiniButton(classLoader);
        hookReliableDownloadTransport(classLoader);
        hookReliableDownloadNotifications(classLoader);
    }

    private void hookReliableDownloadButton(ClassLoader classLoader) {
        try {
            Class<?> cellClass = classLoader.loadClass("org.telegram.ui.Cells.ChatMessageCell");
            Method method = Reflect.method(cellClass, "didPressButton", boolean.class, boolean.class);
            deoptimize(method, "ChatMessageCell.didPressButton(boolean, boolean)");
            hook(method, chain -> {
                Object[] buttonArgs = chain.getArgs().toArray(new Object[0]);
                reliableVideoDownloads.onUserButton(chain.getThisObject(), buttonArgs);
                Object result = chain.proceed();
                reliableVideoDownloads.onUserButtonComplete(chain.getThisObject(), buttonArgs);
                return result;
            });
            info("ReliableDownload: hooked ChatMessageCell.didPressButton");
        } catch (Throwable throwable) {
            error("ReliableDownload: failed to hook user download button", throwable);
        }
    }

    private void hookReliableDownloadMiniButton(ClassLoader classLoader) {
        try {
            Class<?> cellClass = classLoader.loadClass("org.telegram.ui.Cells.ChatMessageCell");
            Method method = Reflect.method(cellClass, "didPressMiniButton", boolean.class);
            deoptimize(method, "ChatMessageCell.didPressMiniButton(boolean)");
            hook(method, chain -> {
                Object[] buttonArgs = chain.getArgs().toArray(new Object[0]);
                if (reliableVideoDownloads.onUserMiniButton(chain.getThisObject(), buttonArgs)) {
                    return null;
                }
                Object result = chain.proceed();
                reliableVideoDownloads.onUserMiniButtonComplete(chain.getThisObject(), buttonArgs);
                return result;
            });
            info("ReliableDownload: hooked ChatMessageCell.didPressMiniButton");
        } catch (Throwable throwable) {
            error("ReliableDownload: failed to hook user mini download button", throwable);
        }
    }

    private void hookReliableDownloadTransport(ClassLoader classLoader) {
        try {
            Class<?> fileLoaderClass = classLoader.loadClass("org.telegram.messenger.FileLoader");
            boolean hooked = false;
            for (Method method : fileLoaderClass.getDeclaredMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                if (!"loadFile".equals(method.getName()) || parameters.length != 4
                        || parameters[2] != int.class || parameters[3] != int.class
                        || method.getReturnType() != void.class) {
                    continue;
                }
                deoptimize(method, "FileLoader.loadFile(Document, Object, int, int)");
                hook(method, chain -> {
                    Object[] loadArgs = chain.getArgs().toArray(new Object[0]);
                    if (!reliableVideoDownloads.onLoadFile(chain.getThisObject(), loadArgs)) {
                        return null;
                    }
                    return chain.proceed();
                });
                hooked = true;
            }
            if (!hooked) {
                throw new NoSuchMethodException("FileLoader.loadFile(Document,Object,int,int)");
            }
            info("ReliableDownload: hooked FileLoader video download transport");
            hookReliableDownloadStreamTransport(fileLoaderClass);
            hookReliableDownloadInternalTransport(fileLoaderClass);
            hookReliableDownloadLoadingQuery(fileLoaderClass);
            hookReliableDownloadVideoLoadingState(fileLoaderClass);
        } catch (Throwable throwable) {
            error("ReliableDownload: failed to hook FileLoader", throwable);
        }
    }

    private void hookReliableDownloadStreamTransport(Class<?> fileLoaderClass) {
        int hooked = 0;
        try {
            for (Method method : fileLoaderClass.getDeclaredMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                if (!"loadStreamFile".equals(method.getName()) || parameters.length < 4
                        || !"org.telegram.tgnet.TLRPC$Document".equals(parameters[1].getName())) {
                    continue;
                }
                deoptimize(method, "FileLoader." + method.getName() + signatureOf(parameters));
                hook(method, chain -> {
                    Object[] loadArgs = chain.getArgs().toArray(new Object[0]);
                    if (!reliableVideoDownloads.onLoadStreamFile(chain.getThisObject(), loadArgs)) {
                        // AnimatedFileDrawableStream and FileStreamLoadOperation accept a null
                        // operation here and stop waiting for the canceled stream.
                        return null;
                    }
                    return chain.proceed();
                });
                hooked++;
            }
            if (hooked == 0) {
                throw new NoSuchMethodException("FileLoader.loadStreamFile(...,Document,...)");
            }
            info("ReliableDownload: hooked FileLoader stream transport overloads=" + hooked);
        } catch (Throwable throwable) {
            error("ReliableDownload: failed to hook FileLoader stream transport", throwable);
        }
    }

    private void hookReliableDownloadInternalTransport(Class<?> fileLoaderClass) {
        int hooked = 0;
        try {
            for (Method method : fileLoaderClass.getDeclaredMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                if (!"loadFileInternal".equals(method.getName()) || parameters.length < 6
                        || !"org.telegram.tgnet.TLRPC$Document".equals(parameters[0].getName())
                        || !"org.telegram.messenger.FileLoadOperation".equals(method.getReturnType().getName())) {
                    continue;
                }
                deoptimize(method, "FileLoader." + method.getName() + signatureOf(parameters));
                hook(method, chain -> {
                    Object[] loadArgs = chain.getArgs().toArray(new Object[0]);
                    if (!reliableVideoDownloads.onLoadFileInternal(chain.getThisObject(), loadArgs)) {
                        // This is the FileLoader queue's operation-creation point. Returning null
                        // also releases the stream caller's CountDownLatch without reviving the
                        // canceled download.
                        return null;
                    }
                    return chain.proceed();
                });
                hooked++;
            }
            if (hooked == 0) {
                throw new NoSuchMethodException("FileLoader.loadFileInternal(Document,...)");
            }
            info("ReliableDownload: hooked FileLoader internal transport overloads=" + hooked);
        } catch (Throwable throwable) {
            error("ReliableDownload: failed to hook FileLoader internal transport", throwable);
        }
    }

    private void hookReliableDownloadLoadingQuery(Class<?> fileLoaderClass) {
        int hooked = 0;
        try {
            for (Method method : fileLoaderClass.getDeclaredMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                if (!"isLoadingFile".equals(method.getName()) || parameters.length != 1
                        || parameters[0] != String.class || method.getReturnType() != boolean.class) {
                    continue;
                }
                deoptimize(method, "FileLoader.isLoadingFile(String)");
                hook(method, chain -> {
                    Object[] queryArgs = chain.getArgs().toArray(new Object[0]);
                    if (!reliableVideoDownloads.onIsLoadingFile(chain.getThisObject(), queryArgs)) {
                        // ChatMessageCell derives the mini X directly from this query. Returning
                        // false removes a stale operation from the UI and asks the manager to
                        // cancel it by file name as a final safety net.
                        return false;
                    }
                    return chain.proceed();
                });
                hooked++;
            }
            if (hooked == 0) {
                throw new NoSuchMethodException("FileLoader.isLoadingFile(String)");
            }
            info("ReliableDownload: hooked FileLoader loading-state query overloads=" + hooked);
        } catch (Throwable throwable) {
            error("ReliableDownload: failed to hook FileLoader loading-state query", throwable);
        }
    }

    private void hookReliableDownloadVideoLoadingState(Class<?> fileLoaderClass) {
        int queries = 0;
        int mutations = 0;
        try {
            for (Method method : fileLoaderClass.getDeclaredMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                boolean documentFirst = parameters.length > 0
                        && "org.telegram.tgnet.TLRPC$Document".equals(parameters[0].getName());
                if ("isLoadingVideo".equals(method.getName()) && parameters.length == 2
                        && documentFirst && parameters[1] == boolean.class
                        && method.getReturnType() == boolean.class) {
                    deoptimize(method, "FileLoader.isLoadingVideo(Document, boolean)");
                    hook(method, chain -> {
                        Object[] queryArgs = chain.getArgs().toArray(new Object[0]);
                        if (!reliableVideoDownloads.onIsLoadingVideo(chain.getThisObject(), queryArgs)) {
                            return false;
                        }
                        return chain.proceed();
                    });
                    queries++;
                } else if (("setLoadingVideo".equals(method.getName()) && parameters.length == 3
                        || "setLoadingVideoForPlayer".equals(method.getName()) && parameters.length == 2)
                        && documentFirst && parameters[1] == boolean.class
                        && method.getReturnType() == void.class) {
                    deoptimize(method, "FileLoader." + method.getName() + signatureOf(parameters));
                    hook(method, chain -> {
                        Object[] stateArgs = chain.getArgs().toArray(new Object[0]);
                        if (!reliableVideoDownloads.onSetLoadingVideo(
                                chain.getThisObject(), stateArgs, method.getName())) {
                            return null;
                        }
                        return chain.proceed();
                    });
                    mutations++;
                }
            }
            if (queries == 0 || mutations == 0) {
                throw new NoSuchMethodException("FileLoader video loading-state methods queries="
                        + queries + " mutations=" + mutations);
            }
            info("ReliableDownload: hooked FileLoader player loading-state queries=" + queries
                    + " mutators=" + mutations);
        } catch (Throwable throwable) {
            error("ReliableDownload: failed to hook FileLoader player loading-state", throwable);
        }
    }

    private void hookReliableDownloadNotifications(ClassLoader classLoader) {
        try {
            Class<?> notificationClass = classLoader.loadClass("org.telegram.messenger.NotificationCenter");
            int progressId = Reflect.asInt(Reflect.staticField(notificationClass, "fileLoadProgressChanged"), -1);
            int loadedId = Reflect.asInt(Reflect.staticField(notificationClass, "fileLoaded"), -1);
            int failedId = Reflect.asInt(Reflect.staticField(notificationClass, "fileLoadFailed"), -1);
            Method method = Reflect.method(notificationClass, "postNotificationName", int.class, Object[].class);
            hook(method, chain -> {
                Object result = chain.proceed();
                java.util.List<Object> hookArgs = chain.getArgs();
                if (hookArgs != null && hookArgs.size() >= 2 && hookArgs.get(1) instanceof Object[]) {
                    reliableVideoDownloads.onNotification(
                            chain.getThisObject(),
                            Reflect.asInt(hookArgs.get(0), -1),
                            (Object[]) hookArgs.get(1),
                            progressId, loadedId, failedId
                    );
                }
                return result;
            });
            info("ReliableDownload: hooked progress notifications ids="
                    + progressId + "/" + loadedId + "/" + failedId);
        } catch (Throwable throwable) {
            error("ReliableDownload: failed to hook progress notifications", throwable);
        }
    }

    private boolean tryHookMessageMethod(Class<?> cellClass, String methodName, Class<?>[] parameterTypes) {
        String signature = methodName + signatureOf(parameterTypes);
        try {
            Method method = Reflect.method(cellClass, methodName, parameterTypes);
            deoptimize(method, "ChatMessageCell." + signature);
            hook(method, this::handleMessageBinding);
            info("Hooked ChatMessageCell." + signature);
            return true;
        } catch (NoSuchMethodException ignored) {
            info("ChatMessageCell." + signature + " not present in this Telegram build");
            return false;
        } catch (Throwable throwable) {
            error("Failed to hook ChatMessageCell." + signature, throwable);
            return false;
        }
    }

    private boolean tryHookCellLifecycleMethod(Class<?> cellClass, String methodName, Class<?>[] parameterTypes) {
        String signature = methodName + signatureOf(parameterTypes);
        try {
            Method method = Reflect.method(cellClass, methodName, parameterTypes);
            deoptimize(method, "ChatMessageCell." + signature);
            hook(method, this::handleCellLifecycle);
            info("Hooked ChatMessageCell." + signature);
            return true;
        } catch (NoSuchMethodException ignored) {
            info("ChatMessageCell." + signature + " not present in this Telegram build");
            return false;
        } catch (Throwable throwable) {
            error("Failed to hook ChatMessageCell." + signature, throwable);
            return false;
        }
    }

    private boolean tryHookCellMeasureMethod(Class<?> cellClass, String methodName, Class<?>[] parameterTypes) {
        String signature = methodName + signatureOf(parameterTypes);
        try {
            Method method = Reflect.method(cellClass, methodName, parameterTypes);
            deoptimize(method, "ChatMessageCell." + signature);
            hook(method, this::handleCellMeasure);
            info("Hooked ChatMessageCell." + signature);
            return true;
        } catch (NoSuchMethodException ignored) {
            info("ChatMessageCell." + signature + " not present in this Telegram build");
            return false;
        } catch (Throwable throwable) {
            error("Failed to hook ChatMessageCell." + signature, throwable);
            return false;
        }
    }

    private static String signatureOf(Class<?>[] parameterTypes) {
        StringBuilder builder = new StringBuilder("(");
        for (int i = 0; i < parameterTypes.length; i++) {
            if (i > 0) {
                builder.append(", ");
            }
            Class<?> parameterType = parameterTypes[i];
            builder.append(parameterType == null ? "null" : parameterType.getSimpleName());
        }
        return builder.append(')').toString();
    }

    private void hookChatActivityMenu(ClassLoader classLoader) {
        try {
            Class<?> chatActivityClass = classLoader.loadClass("org.telegram.ui.ChatActivity");
            Method createView = Reflect.method(chatActivityClass, "createView", Context.class);
            hook(createView, chain -> {
                Object result = chain.proceed();
                try {
                    injectChatMenu(chain.getThisObject());
                } catch (Throwable throwable) {
                    error("Chat menu injection failed", throwable);
                }
                return result;
            });
            info("Hooked ChatActivity menu");
        } catch (Throwable throwable) {
            error("Failed to hook ChatActivity menu", throwable);
        }
    }

    private void hookChatActivityResume(ClassLoader classLoader) {
        try {
            Class<?> chatActivityClass = classLoader.loadClass("org.telegram.ui.ChatActivity");
            Method onResume = Reflect.method(chatActivityClass, "onResume");
            hook(onResume, chain -> {
                Object result = chain.proceed();
                try {
                    Object chatActivity = chain.getThisObject();
                    refreshChatActivityFiltering(chatActivity);
                    beginReadPositionTracking(chatActivity);
                } catch (Throwable throwable) {
                    error("ChatActivity resume refresh failed", throwable);
                }
                // Build #27: menu items injected at createView can be
                // missed when the chat is not loaded yet (currentChat
                // null, broadcast check fails, add-to-folder skipped
                // permanently for that session). Re-inject on resume,
                // when the chat is loaded; injectChatMenu is idempotent
                // for items already present.
                try {
                    injectChatMenu(chain.getThisObject());
                } catch (Throwable ignored) {
                }
                try {
                    enableAutoTranslateForChat(chain.getThisObject());
                } catch (Throwable throwable) {
                    error("Auto-translate on resume failed", throwable);
                }
                return result;
            });
            info("Hooked ChatActivity.onResume refresh");
        } catch (Throwable throwable) {
            error("Failed to hook ChatActivity.onResume", throwable);
        }
    }

    private void hookChatActivityPause(ClassLoader classLoader) {
        try {
            Class<?> chatActivityClass = classLoader.loadClass("org.telegram.ui.ChatActivity");
            Method onPause = Reflect.method(chatActivityClass, "onPause");
            hook(onPause, chain -> {
                try {
                    Object chatActivity = chain.getThisObject();
                    flushReadPosition(chatActivity);
                    markLoadedFilteredMessagesAsRead(chatActivity);
                } catch (Throwable throwable) {
                    error("ChatActivity pause flush failed", throwable);
                }
                return chain.proceed();
            });
            info("Hooked ChatActivity.onPause read position flush");
        } catch (Throwable throwable) {
            error("Failed to hook ChatActivity.onPause", throwable);
        }
    }

    private void hookScrollToLastMessage(ClassLoader classLoader) {
        try {
            Class<?> chatActivityClass = classLoader.loadClass("org.telegram.ui.ChatActivity");
            Method scrollToLast = Reflect.method(
                    chatActivityClass,
                    "scrollToLastMessage",
                    boolean.class,
                    boolean.class,
                    Runnable.class
            );
            hook(scrollToLast, chain -> {
                try {
                    Object chatActivity = chain.getThisObject();
                    saveReadPositionBeforeJump(chatActivity);
                } catch (Throwable throwable) {
                    error("scrollToLastMessage pre-save failed", throwable);
                }
                return chain.proceed();
            });
            info("Hooked ChatActivity.scrollToLastMessage");
        } catch (Throwable throwable) {
            error("Failed to hook ChatActivity.scrollToLastMessage", throwable);
        }
    }

    private void saveReadPositionBeforeJump(Object chatActivity) {
        if (suppressNextSaveBeforeJump) {
            return;
        }
        long dialogId = trackedDialogId;
        int currentPos = lastTopmostMessageId;
        if (dialogId == 0L || currentPos <= 0) {
            return;
        }
        Context context = resolveContextFromActivity(chatActivity);
        if (context == null) {
            return;
        }
        ChatReadPositionStore.save(context.getApplicationContext(), dialogId, currentPos);
        jumpDetected = true;
        info("SaveBeforeJump: saved position " + currentPos + " for dialog " + dialogId);
    }

    private void hookMessageContextMenu(ClassLoader classLoader) {
        // Conso fix: ChatActivity defines FOUR createMenu overloads in Conso
        // 2.0.0 (6 / 7 / 8 / 10 params, verified from Conso's own dex):
        //   (View, Z, Z, F, F, Z)
        //   (View, Z, Z, F, F, Z, Z)
        //   (View, Z, Z, F, F, Z, Z, Z)               <- the only one upstream hooked
        //   (View, Z, Z, F, F, Z, Z, Z, MessageObject, MessageObject)
        // Upstream only hooked the 8-param overload. If Conso's long-press path
        // calls a different overload (the 10-param one carries the message
        // objects, which is exactly what a message menu needs), the hook never
        // fires and no Block/Mark/Smart-block item ever appears -- which is
        // exactly what the user reported, while the chat menu (createView,
        // a single stable method) kept working. Hook every boolean-returning
        // createMenu overload whose first parameter is a View.
        try {
            Class<?> chatActivityClass = classLoader.loadClass("org.telegram.ui.ChatActivity");
            int hooked = 0;
            for (Method candidate : chatActivityClass.getDeclaredMethods()) {
                if (!"createMenu".equals(candidate.getName())) {
                    continue;
                }
                Class<?>[] params = candidate.getParameterTypes();
                if (candidate.getReturnType() != boolean.class
                        || params.length == 0
                        || params[0] != View.class) {
                    continue;
                }
                candidate.setAccessible(true);
                deoptimize(candidate, "ChatActivity.createMenu/" + params.length + " params");
                Method method = candidate;
                hook(method, chain -> {
                    Object result = chain.proceed();
                    try {
                        if (Boolean.TRUE.equals(result) && chain.getArg(0) instanceof View) {
                            // For the 10-param overload, args 8/9 are MessageObjects;
                            // prefer them over the selectedObject field when present.
                            Object preferred = null;
                            for (int i = 8; i < chain.getArgs().size(); i++) {
                                Object arg = chain.getArg(i);
                                if (arg != null && arg.getClass().getName().equals("org.telegram.messenger.MessageObject")) {
                                    preferred = arg;
                                    break;
                                }
                            }
                            injectMessageBlockMenu(chain.getThisObject(), (View) chain.getArg(0), preferred);
                        }
                    } catch (Throwable throwable) {
                        error("Message context menu injection failed", throwable);
                    }
                    return result;
                });
                hooked++;
            }
            if (hooked == 0) {
                throw new NoSuchMethodException("No ChatActivity.createMenu(View, ...) overload found");
            }
            info("Hooked ChatActivity message context menu, overloads=" + hooked);
        } catch (Throwable throwable) {
            error("Failed to hook ChatActivity message context menu", throwable);
        }
    }

    private void hookChatActivityAdapter(ClassLoader classLoader) {
        try {
            Class<?> adapterClass = classLoader.loadClass("org.telegram.ui.ChatActivity$ChatActivityAdapter");
            Class<?> viewHolderClass = classLoader.loadClass("androidx.recyclerview.widget.RecyclerView$ViewHolder");
            Method onBindViewHolder = Reflect.method(adapterClass, "onBindViewHolder", viewHolderClass, int.class);
            deoptimize(onBindViewHolder, "ChatActivityAdapter.onBindViewHolder(ViewHolder, int)");
            hook(onBindViewHolder, this::handleChatRowBinding);
            info("Hooked ChatActivityAdapter.onBindViewHolder(ViewHolder, int)");
        } catch (Throwable throwable) {
            error("Failed to hook ChatActivityAdapter", throwable);
        }
    }

    private void hookRecyclerViewBinding(ClassLoader classLoader) {
        try {
            Class<?> adapterClass = classLoader.loadClass("androidx.recyclerview.widget.RecyclerView$Adapter");
            Class<?> recyclerClass = classLoader.loadClass("androidx.recyclerview.widget.RecyclerView$Recycler");
            Class<?> viewHolderClass = classLoader.loadClass("androidx.recyclerview.widget.RecyclerView$ViewHolder");

            Method bindViewHolder = Reflect.method(adapterClass, "bindViewHolder", viewHolderClass, int.class);
            deoptimize(bindViewHolder, "RecyclerView.Adapter.bindViewHolder(ViewHolder, int)");
            hook(bindViewHolder, this::handleRecyclerViewBinding);
            info("Hooked RecyclerView.Adapter.bindViewHolder(ViewHolder, int)");

            Method onViewAttachedToWindow = Reflect.method(adapterClass, "onViewAttachedToWindow", viewHolderClass);
            deoptimize(onViewAttachedToWindow, "RecyclerView.Adapter.onViewAttachedToWindow(ViewHolder)");
            hook(onViewAttachedToWindow, this::handleRecyclerViewAttachment);
            info("Hooked RecyclerView.Adapter.onViewAttachedToWindow(ViewHolder)");

            Method tryBindViewHolderByDeadline = Reflect.method(
                    recyclerClass,
                    "tryBindViewHolderByDeadline",
                    viewHolderClass,
                    int.class,
                    int.class,
                    long.class
            );
            deoptimize(tryBindViewHolderByDeadline, "RecyclerView.Recycler.tryBindViewHolderByDeadline(ViewHolder, int, int, long)");
        } catch (Throwable throwable) {
            error("Failed to hook RecyclerView binding", throwable);
        }
    }

    private void hookProfileSettingsMenu(ClassLoader classLoader) {
        try {
            Class<?> profileActivityClass = classLoader.loadClass("org.telegram.ui.ProfileActivity");
            Method createActionBarMenu = Reflect.method(profileActivityClass, "createActionBarMenu", boolean.class);
            hook(createActionBarMenu, chain -> {
                Object result = chain.proceed();
                try {
                    injectGlobalSettingsMenu(chain.getThisObject(), true);
                } catch (Throwable throwable) {
                    error("ProfileActivity menu injection failed", throwable);
                }
                return result;
            });
            info("Hooked ProfileActivity settings menu");
        } catch (Throwable throwable) {
            error("Failed to hook ProfileActivity menu", throwable);
        }
    }

    private void hookDownloadActivityMenu(ClassLoader classLoader) {
        try {
            Class<?> dialogsClass = classLoader.loadClass("org.telegram.ui.DialogsActivity");
            Method createView = Reflect.method(dialogsClass, "createView", Context.class);
            hook(createView, chain -> {
                Object contextArg = chain.getArg(0);
                if (contextArg instanceof Context) {
                    try {
                        initAntiRecallFromContext((Context) contextArg, "dialogs");
                    } catch (Throwable throwable) {
                        error("DialogsActivity anti-recall init failed", throwable);
                    }
                }
                Object result = chain.proceed();
                try {
                    Object activity = chain.getThisObject();
                    Object actionBar = Reflect.field(activity, "actionBar");
                    if (actionBar instanceof ViewGroup) {
                        View bar = (ViewGroup) actionBar;
                        Object activityRef = activity;
                        bar.postDelayed(() -> {
                            try {
                                startKeepVisibleLoop((ViewGroup) bar);
                                installActionModeDetector((ViewGroup) bar);
                                // After download button auto-clicks, also install detector on
                                // the download page's internal ActionBarMenu
                                bar.postDelayed(() -> {
                                    try {
                                        installActionModeDetectorOnDownloadPage((ViewGroup) bar, activityRef);
                                    } catch (Throwable t) {
                                        error("SelectAll: download page detector failed", t);
                                    }
                                }, 8000);
                            } catch (Throwable t) {
                                error("SelectAll: loop failed", t);
                            }
                        }, 500);
                    }
                } catch (Throwable t) {
                    error("SelectAll: createView hook failed", t);
                }
                return result;
            });
            info("Hooked DialogsActivity.createView for download button");
        } catch (Throwable t) {
            error("Failed to hook DialogsActivity.createView", t);
        }
    }

    private void hookDialogDeletionDiagnostics(ClassLoader classLoader) {
        try {
            Class<?> dialogsClass = classLoader.loadClass("org.telegram.ui.DialogsActivity");
            int hooked = 0;
            for (Method method : dialogsClass.getDeclaredMethods()) {
                String name = method.getName();
                if (!"performSelectedDialogsAction".equals(name)
                        && !"performDeleteOrClearDialogAction".equals(name)
                        && !name.startsWith("lambda$performSelectedDialogsAction$")) {
                    continue;
                }
                String methodName = name;
                int paramCount = method.getParameterCount();
                hook(method, chain -> {
                    try {
                        info("DialogDeleteTrace: " + methodName + " params=" + paramCount
                                + describeHookArgs(chain.getArgs()));
                    } catch (Throwable throwable) {
                        error("DialogDeleteTrace: failed before " + methodName, throwable);
                    }
                    recordDialogDeletionIntentFromUi(methodName, chain.getThisObject(), chain.getArgs());
                    return chain.proceed();
                });
                hooked++;
            }
            info("DialogDeleteTrace: hooked DialogsActivity deletion methods count=" + hooked);
        } catch (Throwable throwable) {
            error("DialogDeleteTrace: failed to hook DialogsActivity deletion methods", throwable);
        }
    }

    private void recordDialogDeletionIntentFromUi(String methodName, Object host, List<Object> args) {
        int action = firstIntArg(args, -1);
        if (action != 0x66) {
            return;
        }
        List<Long> dialogIds = dialogIdsFromArgs(args);
        if (dialogIds.isEmpty() || !isCommittedDialogDeleteCall(methodName)) {
            return;
        }
        if (recallDetector == null) {
            initAntiRecallDeferred();
        }
        RecallDetector detector = recallDetector;
        if (detector == null) {
            info("DialogDeleteTrace: ui delete not marked, anti-recall unavailable count=" + dialogIds.size());
            return;
        }
        boolean revoke = lastBooleanArg(args, false);
        boolean shouldPruneDatabase = shouldPruneDialogDatabaseForCall(methodName);
        int account = resolveDialogDeletionAccount(host);
        int marked = 0;
        for (Long dialogId : dialogIds) {
            if (dialogId != null && dialogId != 0L
                    && detector.processDialogDeletion(dialogId, action, revoke, "uiDeleteDialog")) {
                markLocalDialogHidden(dialogId, account);
                if (shouldPruneDatabase) {
                    pruneTelegramDialogDatabase(dialogId, account, methodName);
                }
                marked++;
            }
        }
        if (marked > 0) {
            info("DialogDeleteTrace: marked local ui delete count=" + marked
                    + " action=" + action + " source=" + methodName);
            refreshDialogsUi(host);
        }
    }

    private void markLocalDialogHidden(long dialogId, int account) {
        LocalDialogDeleteStore.HiddenDialog hiddenDialog =
                new LocalDialogDeleteStore.HiddenDialog(account, dialogId);
        locallyHiddenDialogs.add(hiddenDialog);
        LocalDialogDeleteStore store = localDialogDeleteStore;
        if (store != null) {
            store.hide(dialogId, hiddenDialog.account);
        }
        info("DialogDeleteTrace: local hide dialogId=" + dialogId + " account=" + hiddenDialog.account);
    }

    private boolean shouldPruneDialogDatabaseForCall(String methodName) {
        return "performDeleteOrClearDialogAction".equals(methodName);
    }

    private void pruneTelegramDialogDatabase(long dialogId, int account, String source) {
        if (dialogId == 0L) {
            return;
        }
        TelegramDialogDatabasePruner pruner = dialogDatabasePruner;
        if (pruner == null) {
            initAntiRecallDeferred();
            pruner = dialogDatabasePruner;
        }
        if (pruner == null) {
            info("DialogDatabasePrune: skipped, pruner unavailable dialogId=" + dialogId
                    + " account=" + account + " source=" + source);
            return;
        }
        pruner.pruneAsync(dialogId, account, source);
    }

    private void pruneKnownHiddenDialogs(Set<LocalDialogDeleteStore.HiddenDialog> hiddenDialogs) {
        if (hiddenDialogs == null || hiddenDialogs.isEmpty()) {
            return;
        }
        TelegramDialogDatabasePruner pruner = dialogDatabasePruner;
        if (pruner == null) {
            return;
        }
        for (LocalDialogDeleteStore.HiddenDialog hiddenDialog : hiddenDialogs) {
            if (hiddenDialog == null || hiddenDialog.dialogId == 0L) {
                continue;
            }
            pruner.pruneAsync(hiddenDialog.dialogId, hiddenDialog.account, "startup-hidden");
        }
    }

    private int resolveDialogDeletionAccount(Object host) {
        int account = Reflect.asInt(Reflect.field(host, "currentAccount"), Integer.MIN_VALUE);
        if (account != Integer.MIN_VALUE) {
            return Math.max(0, account);
        }
        Object getter = Reflect.invokeIfExists(host, "getCurrentAccount", new Class<?>[0]);
        account = Reflect.asInt(getter, Integer.MIN_VALUE);
        if (account != Integer.MIN_VALUE) {
            return Math.max(0, account);
        }
        return resolveSelectedTelegramAccount(savedClassLoader);
    }

    private boolean isLocallyHiddenDialog(long dialogId, int account) {
        if (dialogId == 0L) {
            return false;
        }
        LocalDialogDeleteStore.HiddenDialog hiddenDialog =
                new LocalDialogDeleteStore.HiddenDialog(account, dialogId);
        if (locallyHiddenDialogs.contains(hiddenDialog)) {
            return true;
        }
        LocalDialogDeleteStore store = localDialogDeleteStore;
        if (store != null && store.isHidden(dialogId, hiddenDialog.account)) {
            locallyHiddenDialogs.add(hiddenDialog);
            return true;
        }
        return false;
    }

    private void refreshDialogsUi(Object host) {
        if (host == null) {
            return;
        }
        try {
            Object viewPages = Reflect.field(host, "viewPages");
            if (!(viewPages instanceof Object[])) {
                return;
            }
            for (Object page : (Object[]) viewPages) {
                Object listView = Reflect.field(page, "listView");
                Object adapter = listView instanceof View
                        ? Reflect.invokeIfExists(listView, "getAdapter", new Class<?>[0])
                        : null;
                if (listView instanceof ViewGroup) {
                    hideVisibleDialogRows((ViewGroup) listView, adapter);
                }
                if (listView instanceof View) {
                    Reflect.invokeIfExists(adapter, "notifyDataSetChanged", new Class<?>[0]);
                    ((View) listView).requestLayout();
                    ((View) listView).invalidate();
                    ((View) listView).post(() -> {
                        try {
                            Object delayedAdapter = Reflect.invokeIfExists(listView, "getAdapter", new Class<?>[0]);
                            if (listView instanceof ViewGroup) {
                                hideVisibleDialogRows((ViewGroup) listView, delayedAdapter);
                            }
                            Reflect.invokeIfExists(delayedAdapter, "notifyDataSetChanged", new Class<?>[0]);
                            ((View) listView).requestLayout();
                            ((View) listView).invalidate();
                        } catch (Throwable throwable) {
                            error("DialogDeleteTrace: delayed refresh dialogs UI failed", throwable);
                        }
                    });
                }
            }
        } catch (Throwable throwable) {
            error("DialogDeleteTrace: refresh dialogs UI failed", throwable);
        }
    }

    private void hideVisibleDialogRows(ViewGroup listView, Object adapter) {
        int account = resolveDialogDeletionAccount(adapter);
        for (int i = 0; i < listView.getChildCount(); i++) {
            View child = listView.getChildAt(i);
            if (isDialogListRow(null, child)) {
                applyLocalDialogHideToRow(child, account);
            }
        }
    }

    static boolean isCommittedDialogDeleteCall(String methodName) {
        return "performDeleteOrClearDialogAction".equals(methodName);
    }

    private int firstIntArg(List<Object> args, int fallback) {
        if (args == null) {
            return fallback;
        }
        for (Object arg : args) {
            if (arg instanceof Integer) {
                return (Integer) arg;
            }
        }
        return fallback;
    }

    private boolean lastBooleanArg(List<Object> args, boolean fallback) {
        if (args == null) {
            return fallback;
        }
        boolean value = fallback;
        for (Object arg : args) {
            if (arg instanceof Boolean) {
                value = (Boolean) arg;
            }
        }
        return value;
    }

    private List<Long> dialogIdsFromArgs(List<Object> args) {
        List<Long> dialogIds = new ArrayList<>();
        if (args == null) {
            return dialogIds;
        }
        for (Object arg : args) {
            if (arg instanceof Long) {
                dialogIds.add((Long) arg);
            } else if (arg instanceof List<?>) {
                for (Object item : (List<?>) arg) {
                    if (item instanceof Long) {
                        dialogIds.add((Long) item);
                    }
                }
            }
        }
        return dialogIds;
    }

    /**
     * Diagnostic hook to find the real selection field for download page.
     * Hooks SearchDownloadsContainer$ExternalSyntheticLambda2.invoke to dump
     * parentFragment and adapter fields before/after onItemClick.
     */
    private void hookOnItemClickDiagnostic(ClassLoader classLoader) {
        try {
            // Enumerate ALL inner classes of SearchDownloadsContainer
            Class<?> containerClass = classLoader.loadClass("org.telegram.ui.Components.SearchDownloadsContainer");
            info("SelectAll: found SearchDownloadsContainer, listing inner classes...");
            Class<?>[] innerClasses = containerClass.getDeclaredClasses();
            for (Class<?> inner : innerClasses) {
                info("SelectAll: inner class: " + inner.getSimpleName() + " -> " + inner.getName());
            }
            // Also list all declared methods
            for (Method m : containerClass.getDeclaredMethods()) {
                info("SelectAll: method: " + m.getName() + " params=" + m.getParameterCount());
            }
            // Hook lambda$new$1 (likely click listener) and lambda$new$0 (likely long-click)
            for (Method m : containerClass.getDeclaredMethods()) {
                String name = m.getName();
                if (name.equals("lambda$new$0") || name.equals("lambda$new$1")) {
                    info("SelectAll: hooking " + name + " params=" + m.getParameterCount());
                    hook(m, chain -> {
                        try {
                            Object thisObj = chain.getThisObject();
                            info("SelectAll: >>> " + name + " called");
                            java.util.Map<String, Object> beforePF = dumpCollectionFields(thisObj, "before");
                            Object result = chain.proceed();
                            java.util.Map<String, Object> afterPF = dumpCollectionFields(thisObj, "after");
                            compareFields(beforePF, afterPF, name);
                            return result;
                        } catch (Throwable t) {
                            error("SelectAll: hook error on " + name, t);
                            return chain.proceed();
                        }
                    });
                }
            }
            // Also hook DownloadsAdapter methods
            try {
                Class<?> adapterClass = classLoader.loadClass("org.telegram.ui.Components.SearchDownloadsContainer$DownloadsAdapter");
                info("SelectAll: found DownloadsAdapter, listing methods...");
                for (Method m : adapterClass.getDeclaredMethods()) {
                    info("SelectAll: adapter method: " + m.getName() + " params=" + m.getParameterCount());
                }
                // Hook onBindViewHolder to dump adapter fields when binding
                for (Method m : adapterClass.getDeclaredMethods()) {
                    if (m.getName().equals("onBindViewHolder")) {
                        info("SelectAll: hooking adapter.onBindViewHolder");
                        hook(m, chain -> {
                            try {
                                Object adapter = chain.getThisObject();
                                info("SelectAll: >>> adapter.onBindViewHolder called");
                                dumpAllFieldsDeep(adapter, "adapter", 1);
                                return chain.proceed();
                            } catch (Throwable t) {
                                error("SelectAll: hook error on onBindViewHolder", t);
                                return chain.proceed();
                            }
                        });
                        break;
                    }
                }
            } catch (ClassNotFoundException e) {
                info("SelectAll: DownloadsAdapter not found: " + e.getMessage());
            }
        } catch (Throwable t) {
            error("SelectAll: hookOnItemClickDiagnostic failed", t);
        }
    }

    /**
     * Dumps all Collection/Map fields from an object for diagnostic comparison.
     */
    private java.util.Map<String, Object> dumpCollectionFields(Object obj, String label) {
        java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
        if (obj == null) return result;

        Class<?> clazz = obj.getClass();
        info("SelectAll: dumping fields for " + label + " class=" + clazz.getSimpleName());

        while (clazz != null && clazz != Object.class) {
            for (java.lang.reflect.Field field : clazz.getDeclaredFields()) {
                try {
                    field.setAccessible(true);
                    Object value = field.get(obj);
                    if (value instanceof java.util.Collection || value instanceof java.util.Map) {
                        result.put(field.getName(), value);
                        int size = getCollectionSize(value);
                        info("SelectAll:   field=" + field.getName() + " type=" + value.getClass().getSimpleName() + " size=" + size);
                    }
                } catch (Throwable ignored) {}
            }
            clazz = clazz.getSuperclass();
        }
        return result;
    }

    /**
     * Gets the size of a Collection or Map.
     */
    private int getCollectionSize(Object obj) {
        if (obj instanceof java.util.Collection) {
            return ((java.util.Collection<?>) obj).size();
        } else if (obj instanceof java.util.Map) {
            return ((java.util.Map<?, ?>) obj).size();
        }
        return -1;
    }

    /**
     * Compares before/after field maps and logs any size changes.
     */
    private void compareFields(java.util.Map<String, Object> before, java.util.Map<String, Object> after, String label) {
        for (java.util.Map.Entry<String, Object> entry : before.entrySet()) {
            String fieldName = entry.getKey();
            Object beforeVal = entry.getValue();
            Object afterVal = after.get(fieldName);

            int beforeSize = getCollectionSize(beforeVal);
            int afterSize = getCollectionSize(afterVal);

            if (beforeSize != afterSize) {
                info("SelectAll: FIELD CHANGED in " + label + ": " + fieldName + " size " + beforeSize + " -> " + afterSize);
            }
        }
    }

    /**
     * Dumps ALL fields (not just Collection/Map) from an object up to a given depth.
     * For primitive/boxed types, logs their value. For objects, recurse.
     */
    private void dumpAllFieldsDeep(Object obj, String label, int maxDepth) {
        if (obj == null || maxDepth <= 0) return;
        Class<?> clazz = obj.getClass();
        while (clazz != null && clazz != Object.class) {
            for (java.lang.reflect.Field field : clazz.getDeclaredFields()) {
                try {
                    field.setAccessible(true);
                    Object value = field.get(obj);
                    if (value instanceof java.util.Collection) {
                        int size = ((java.util.Collection<?>) value).size();
                        info("SelectAll:   " + label + "." + field.getName() + "=" + value.getClass().getSimpleName() + "[" + size + "]");
                    } else if (value instanceof java.util.Map) {
                        int size = ((java.util.Map<?, ?>) value).size();
                        info("SelectAll:   " + label + "." + field.getName() + "=" + value.getClass().getSimpleName() + "[" + size + "]");
                    } else if (value instanceof android.util.LongSparseArray) {
                        info("SelectAll:   " + label + "." + field.getName() + "=LongSparseArray[" + ((android.util.LongSparseArray<?>) value).size() + "]");
                    } else if (value instanceof android.util.SparseArray) {
                        info("SelectAll:   " + label + "." + field.getName() + "=SparseArray[" + ((android.util.SparseArray<?>) value).size() + "]");
                    } else if (value != null && field.getType().isArray()) {
                        int len = java.lang.reflect.Array.getLength(value);
                        info("SelectAll:   " + label + "." + field.getName() + "=" + value.getClass().getSimpleName() + "[" + len + "]");
                    } else if (value instanceof Number || value instanceof Boolean || value instanceof String) {
                        info("SelectAll:   " + label + "." + field.getName() + "=" + value);
                    }
                } catch (Throwable ignored) {}
            }
            clazz = clazz.getSuperclass();
        }
    }

    private void startKeepVisibleLoop(ViewGroup actionBar) {
        // 不再添加自定义下载按钮，完全依赖原生逻辑
        info("SelectAll: skipping custom download button, using native");
    }

    private ViewGroup findActionBarMenu(ViewGroup actionBar) {
        for (int i = 0; i < actionBar.getChildCount(); i++) {
            View child = actionBar.getChildAt(i);
            if (child.getClass().getSimpleName().contains("ActionBarMenu") && child instanceof ViewGroup) {
                return (ViewGroup) child;
            }
        }
        return null;
    }

    /**
     * Installs an {@link ViewGroup.OnHierarchyChangeListener} on the ActionBarMenu
     * so we detect action mode (selection mode) the moment Telegram adds its
     * action-mode views. When a numeric TextView (selection count) appears,
     * we inject a "Select All" button into the same menu.
     */
    private void installActionModeDetector(ViewGroup actionBar) {
        ViewGroup menu = findActionBarMenu(actionBar);
        if (menu == null) {
            info("SelectAll: ActionBarMenu not found for detector");
            return;
        }
        info("SelectAll: installing action mode detector on ActionBarMenu children=" + menu.getChildCount());
        menu.setOnHierarchyChangeListener(new ViewGroup.OnHierarchyChangeListener() {
            @Override
            public void onChildViewAdded(View parent, View child) {
                try {
                    if (menu.findViewWithTag(MENU_ID_SELECT_ALL) != null) {
                        return;
                    }
                    if (isActionModeIndicator(child)) {
                        info("SelectAll: action mode detected via child added: " + child.getClass().getSimpleName());
                        menu.postDelayed(() -> {
                            try {
                                if (menu.findViewWithTag(MENU_ID_SELECT_ALL) == null) {
                                    // Check if we're on the download page
                                    if (downloadPageFragmentView != null) {
                                        info("SelectAll: on download page, injecting Select All into action mode");
                                        injectSelectAllIntoActionModeForDownload(menu, downloadPageFragmentView);
                                    } else {
                                        injectSelectAllIntoActionMode(menu);
                                    }
                                }
                            } catch (Throwable t) {
                                error("SelectAll: delayed inject failed", t);
                            }
                        }, 300);
                    }
                } catch (Throwable t) {
                    error("SelectAll: hierarchy listener error", t);
                }
            }

            @Override
            public void onChildViewRemoved(View parent, View child) {
            }
        });
        // Also check existing children in case action mode is already active
        for (int i = 0; i < menu.getChildCount(); i++) {
            if (isActionModeIndicator(menu.getChildAt(i))) {
                info("SelectAll: action mode already active, injecting now");
                if (downloadPageFragmentView != null) {
                    injectSelectAllIntoActionModeForDownload(menu, downloadPageFragmentView);
                } else {
                    injectSelectAllIntoActionMode(menu);
                }
                return;
            }
        }
    }

    /**
     * After the download page loads, polls for action mode activation.
     * When action mode buttons (转到消息, 转发, 删除) appear, injects "Select All".
     */
    private void installActionModeDetectorOnDownloadPage(ViewGroup actionBar, Object activity) {
        View fragmentView = resolveFragmentView(activity);
        if (!(fragmentView instanceof ViewGroup)) {
            info("SelectAll: no fragmentView for download page");
            return;
        }
        java.util.List<View> containers = new java.util.ArrayList<>();
        findAllViewsByClassName((ViewGroup) fragmentView, "SearchDownloadsContainer", containers, 0);
        if (containers.isEmpty()) {
            info("SelectAll: not on download page");
            return;
        }
        info("SelectAll: download page detected, polling for action mode");
        ViewGroup fragmentViewRef = (ViewGroup) fragmentView;
        // Poll for action mode buttons
        actionBar.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!actionBar.isAttachedToWindow()) return;
                    // Check ActionBar itself for action mode buttons
                    boolean actionModeActive = false;
                    ViewGroup actionModeMenu = null;
                    for (int i = 0; i < actionBar.getChildCount(); i++) {
                        View child = actionBar.getChildAt(i);
                        if (child instanceof ViewGroup) {
                            ViewGroup group = (ViewGroup) child;
                            for (int j = 0; j < group.getChildCount(); j++) {
                                View grandchild = group.getChildAt(j);
                                String desc = grandchild.getContentDescription() != null ? grandchild.getContentDescription().toString() : "";
                                if (desc.contains("转到消息") || desc.contains("删除") || desc.contains("转发") ||
                                    desc.contains("Go to") || desc.contains("Delete") || desc.contains("Forward")) {
                                    actionModeActive = true;
                                    actionModeMenu = group;
                                    break;
                                }
                            }
                        }
                    }
                    if (actionModeActive && actionModeMenu != null && actionModeMenu.findViewWithTag(MENU_ID_SELECT_ALL) == null) {
                        info("SelectAll: action mode detected, injecting Select All into action mode menu");
                        injectSelectAllIntoActionModeForDownload(actionModeMenu, fragmentViewRef);
                    }
                    actionBar.postDelayed(this, 500);
                } catch (Throwable t) {
                    error("SelectAll: poll error", t);
                    actionBar.postDelayed(this, 1000);
                }
            }
        }, 500);
    }

    /**
     * Injects "Select All" button next to the action buttons (like delete)
     * that appear when selection mode is active.
     */
    private void injectSelectAllNextToActionButton(ViewGroup menu, ViewGroup actionBar, ViewGroup fragmentView) {
        if (menu.findViewWithTag(MENU_ID_SELECT_ALL) != null) {
            return;
        }
        Context context = actionBar.getContext();
        CharSequence label = isChineseLocale(context) ? "全选" : "Select All";
        TextView button = new TextView(context);
        button.setTag(MENU_ID_SELECT_ALL);
        button.setText(label);
        button.setTextSize(14);
        button.setTextColor(0xFFFFFFFF);
        button.setPadding(dp(context, 12), 0, dp(context, 12), 0);
        button.setGravity(android.view.Gravity.CENTER);
        button.setBackgroundColor(0x33FFFFFF);
        ViewGroup.LayoutParams lp = new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
        // Inject into ActionBarMenu (not ActionBar container) so it appears
        // alongside the action buttons (delete, forward, etc.)
        int insertIndex = menu.getChildCount();
        menu.addView(button, insertIndex, lp);
        menu.invalidate();
        menu.requestLayout();
        info("SelectAll: injected into ActionBarMenu at index=" + insertIndex + " children=" + menu.getChildCount());
        button.setOnClickListener(v -> {
            try {
                info("SelectAll: download page Select All clicked");
                selectAllDownloadItems(fragmentView);
            } catch (Throwable t) {
                error("SelectAll: download page select all failed", t);
            }
        });
    }

    /**
     * Selects all download items by directly writing message IDs to
     * parentFragment.selectedDialogs and refreshing the adapter.
     */


    private void selectAllDownloadItems(ViewGroup fragmentView) {
        java.util.List<View> containers = new java.util.ArrayList<>();
        findAllViewsByClassName(fragmentView, "SearchDownloadsContainer", containers, 0);
        if (containers.isEmpty()) {
            info("SelectAll: SearchDownloadsContainer not found");
            return;
        }
        Object container = containers.get(0);
        Object adapter = Reflect.field(container, "adapter");
        if (adapter == null) {
            info("SelectAll: no adapter on SearchDownloadsContainer");
            return;
        }
        info("SelectAll: adapter=" + adapter.getClass().getSimpleName());

        // Get ALL items from adapter (not just visible cells)
        Object adapterObj = adapter;
        Integer itemCount = Reflect.asInt(Reflect.invokeIfExists(adapterObj, "getItemCount", new Class<?>[0]), 0);
        info("SelectAll: adapter has " + itemCount + " items total");
        if (itemCount <= 0) return;

        // Find getMessage method on adapter
        java.lang.reflect.Method getMessageMethod = null;
        for (java.lang.reflect.Method m : adapterObj.getClass().getDeclaredMethods()) {
            if (m.getName().equals("getMessage") && m.getParameterCount() == 1) {
                getMessageMethod = m;
                getMessageMethod.setAccessible(true);
                break;
            }
        }
        if (getMessageMethod == null) {
            info("SelectAll: getMessage not found on adapter");
            return;
        }

        // Find uiCallback (SearchViewPager) from the container
        // SearchDownloadsContainer delegates all selection to uiCallback.toggleItemSelection()
        Object uiCallback = Reflect.field(container, "uiCallback");
        if (uiCallback == null) {
            info("SelectAll: uiCallback not found on container");
            return;
        }
        info("SelectAll: uiCallback class=" + uiCallback.getClass().getSimpleName());

        // Find toggleItemSelection method by searching the class hierarchy
        java.lang.reflect.Method toggleMethod = null;
        Class<?> cl = uiCallback.getClass();
        while (cl != null && cl != Object.class) {
            for (java.lang.reflect.Method m : cl.getDeclaredMethods()) {
                if (m.getName().equals("toggleItemSelection") && m.getParameterCount() == 3) {
                    toggleMethod = m;
                    toggleMethod.setAccessible(true);
                    info("SelectAll: found toggleItemSelection on " + cl.getSimpleName() + " params=" + m.getParameterTypes()[0].getSimpleName() + "," + m.getParameterTypes()[1].getSimpleName() + "," + m.getParameterTypes()[2].getSimpleName());
                    break;
                }
            }
            if (toggleMethod != null) break;
            cl = cl.getSuperclass();
        }
        // Also check interfaces
        if (toggleMethod == null) {
            for (Class<?> iface : uiCallback.getClass().getInterfaces()) {
                for (java.lang.reflect.Method m : iface.getDeclaredMethods()) {
                    if (m.getName().equals("toggleItemSelection") && m.getParameterCount() == 3) {
                        toggleMethod = m;
                        toggleMethod.setAccessible(true);
                        info("SelectAll: found toggleItemSelection on interface " + iface.getSimpleName());
                        break;
                    }
                }
                if (toggleMethod != null) break;
            }
        }

        if (toggleMethod == null) {
            info("SelectAll: toggleItemSelection not found, dumping all methods");
            for (java.lang.reflect.Method m : uiCallback.getClass().getDeclaredMethods()) {
                info("SelectAll: uiCallback method: " + m.getName() + "(" + m.getParameterCount() + ")");
            }
            return;
        }

        // Find selectedFiles on uiCallback to know which items are already selected
        java.util.Set<Object> alreadySelectedIds = new java.util.HashSet<>();
        Object selectedFiles = Reflect.field(uiCallback, "selectedFiles");
        if (selectedFiles instanceof java.util.Map) {
            java.util.Map<?, ?> sf = (java.util.Map<?, ?>) selectedFiles;
            info("SelectAll: selectedFiles has " + sf.size() + " entries");
            // Extract message IDs from the keys - dump key fields
            for (Object key : sf.keySet()) {
                Class<?> kc = key.getClass();
                while (kc != null && kc != Object.class) {
                    for (java.lang.reflect.Field f : kc.getDeclaredFields()) {
                        try {
                            f.setAccessible(true);
                            Object val = f.get(key);
                            info("SelectAll: key." + f.getName() + "=" + val);
                            if (val instanceof Integer) alreadySelectedIds.add(val);
                        } catch (Throwable ignored) {}
                    }
                    kc = kc.getSuperclass();
                }
            }
            info("SelectAll: already selected IDs: " + alreadySelectedIds.size());
        }

        // Build a map of visible message ID -> SharedDocumentCell view
        java.util.Map<Integer, View> visibleViews = new java.util.HashMap<>();
        java.util.List<View> cells = new java.util.ArrayList<>();
        findAllViewsByClassName(fragmentView, "SharedDocumentCell", cells, 0);
        for (View cell : cells) {
            if (cell.getVisibility() != View.VISIBLE) continue;
            Object msg = Reflect.field(cell, "message");
            if (msg == null) continue;
            Object idObj = Reflect.invokeIfExists(msg, "getId", new Class<?>[0]);
            if (idObj instanceof Integer) visibleViews.put((Integer) idObj, cell);
        }
        info("SelectAll: " + visibleViews.size() + " visible cells mapped");

        // Call toggleItemSelection for ALL adapter items, SKIP already-selected
        // For visible items, pass the actual view so checkmark appears
        int selected = 0;
        for (int i = 0; i < itemCount; i++) {
            Object message;
            try {
                message = getMessageMethod.invoke(adapterObj, i);
            } catch (Throwable ignored) { continue; }
            if (message == null) continue;
            Object msgIdObj = Reflect.invokeIfExists(message, "getId", new Class<?>[0]);
            int msgId = msgIdObj instanceof Integer ? (Integer) msgIdObj : 0;
            if (alreadySelectedIds.contains(msgId)) continue;
            View cellView = visibleViews.get(msgId);
            try {
                toggleMethod.invoke(uiCallback, message, cellView, 0);
                selected++;
            } catch (Throwable t) {
                info("SelectAll: toggle error at " + i + ": " + (t.getCause() != null ? t.getCause().getMessage() : t.getMessage()));
            }
        }
        info("SelectAll: " + selected + " new items selected, " + alreadySelectedIds.size() + " preserved");
    }

    /**
     * Installs the hierarchy change detector on a specific ActionBarMenu ViewGroup.
     */
    private void installHierarchyDetector(ViewGroup menu) {
        if (menu.findViewWithTag(MENU_ID_SELECT_ALL) != null) {
            return;
        }
        menu.setOnHierarchyChangeListener(new ViewGroup.OnHierarchyChangeListener() {
            @Override
            public void onChildViewAdded(View parent, View child) {
                try {
                    if (menu.findViewWithTag(MENU_ID_SELECT_ALL) != null) {
                        return;
                    }
                    if (isActionModeIndicator(child)) {
                        info("SelectAll: action mode detected via child added: " + child.getClass().getSimpleName());
                        menu.postDelayed(() -> {
                            try {
                                if (menu.findViewWithTag(MENU_ID_SELECT_ALL) == null) {
                                    injectSelectAllIntoActionMode(menu);
                                }
                            } catch (Throwable t) {
                                error("SelectAll: delayed inject failed", t);
                            }
                        }, 300);
                    }
                } catch (Throwable t) {
                    error("SelectAll: hierarchy listener error", t);
                }
            }

            @Override
            public void onChildViewRemoved(View parent, View child) {
            }
        });
        // Check existing children
        for (int i = 0; i < menu.getChildCount(); i++) {
            if (isActionModeIndicator(menu.getChildAt(i))) {
                info("SelectAll: action mode already active on fragment menu, injecting now");
                injectSelectAllIntoActionMode(menu);
                return;
            }
        }
    }

    /**
     * Returns true if the view looks like a Telegram action mode indicator.
     * Typical indicators: a TextView showing the selection count (e.g. "1", "5"),
     * or a close/back button that only appears in action mode.
     */
    private boolean isActionModeIndicator(View view) {
        if (view instanceof TextView) {
            String text = ((TextView) view).getText().toString().trim();
            if (text.matches("\\d+")) {
                return true;
            }
        }
        // Check if it's a ViewGroup containing a numeric TextView (nested indicator)
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (child instanceof TextView) {
                    String text = ((TextView) child).getText().toString().trim();
                    if (text.matches("\\d+")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void findAllViewsByClassName(ViewGroup group, String nameFragment, java.util.List<View> result, int depth) {
        if (depth > 15) return;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child.getClass().getSimpleName().contains(nameFragment)) {
                result.add(child);
            }
            if (child instanceof ViewGroup) {
                findAllViewsByClassName((ViewGroup) child, nameFragment, result, depth + 1);
            }
        }
    }

    private View findSubItemDeep(ViewGroup group, int depth) {
        if (depth > 5) return null;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child.getClass().getSimpleName().contains("ActionBarMenuSubItem")) {
                return child;
            }
            if (child instanceof ViewGroup) {
                View found = findSubItemDeep((ViewGroup) child, depth + 1);
                if (found != null) return found;
            }
        }
        return null;
    }

    private View findDownloadButton(ViewGroup actionBar) {
        for (int i = 0; i < actionBar.getChildCount(); i++) {
            View child = actionBar.getChildAt(i);
            if (child.getClass().getSimpleName().contains("ActionBarMenu")) {
                ViewGroup menu = (ViewGroup) child;
                for (int j = 0; j < menu.getChildCount(); j++) {
                    View item = menu.getChildAt(j);
                    if (item instanceof ViewGroup) {
                        ViewGroup vg = (ViewGroup) item;
                        for (int k = 0; k < vg.getChildCount(); k++) {
                            if (vg.getChildAt(k).getClass().getSimpleName().contains("DownloadProgress")) {
                                return item;
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    private View forceDownloadButtonVisible(ViewGroup actionBar) {
        for (int i = 0; i < actionBar.getChildCount(); i++) {
            View child = actionBar.getChildAt(i);
            if (child.getClass().getSimpleName().contains("ActionBarMenu")) {
                ViewGroup menu = (ViewGroup) child;
                for (int j = 0; j < menu.getChildCount(); j++) {
                    View item = menu.getChildAt(j);
                    if (item instanceof ViewGroup) {
                        ViewGroup vg = (ViewGroup) item;
                        for (int k = 0; k < vg.getChildCount(); k++) {
                            if (vg.getChildAt(k).getClass().getSimpleName().contains("DownloadProgress")) {
                                View downloadBtn = item;
                                // 诊断日志：输出按钮状态
                                int vis = downloadBtn.getVisibility();
                                int w = downloadBtn.getWidth();
                                int h = downloadBtn.getHeight();
                                ViewGroup.LayoutParams lp = downloadBtn.getLayoutParams();
                                info("SelectAll: downloadBtn vis=" + vis + " w=" + w + " h=" + h + " lp=" + (lp != null ? lp.getClass().getSimpleName() : "null"));
                                if (lp != null) {
                                    info("SelectAll: lp.width=" + lp.width + " lp.height=" + lp.height);
                                }
                                // 检查父容器
                                ViewParent parent = downloadBtn.getParent();
                                if (parent instanceof ViewGroup) {
                                    ViewGroup parentVg = (ViewGroup) parent;
                                    int parentVis = parentVg.getVisibility();
                                    int parentW = parentVg.getWidth();
                                    int parentH = parentVg.getHeight();
                                    info("SelectAll: parent vis=" + parentVis + " w=" + parentW + " h=" + parentH + " class=" + parentVg.getClass().getSimpleName());
                                }
                                // 强制设置尺寸并显示
                                downloadBtn.setVisibility(View.VISIBLE);
                                if (lp != null && (lp.width <= 0 || lp.height <= 0)) {
                                    int size = dp(downloadBtn.getContext(), 48);
                                    lp.width = size;
                                    lp.height = size;
                                    downloadBtn.setLayoutParams(lp);
                                    info("SelectAll: forced size to " + size);
                                }
                                return item;
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    private void makeDownloadButtonVisible(ViewGroup actionBar) {
        for (int i = 0; i < actionBar.getChildCount(); i++) {
            View child = actionBar.getChildAt(i);
            if (child.getClass().getSimpleName().contains("ActionBarMenu")) {
                ViewGroup menu = (ViewGroup) child;
                for (int j = 0; j < menu.getChildCount(); j++) {
                    View item = menu.getChildAt(j);
                    if (item instanceof ViewGroup) {
                        ViewGroup vg = (ViewGroup) item;
                        for (int k = 0; k < vg.getChildCount(); k++) {
                            if (vg.getChildAt(k).getClass().getSimpleName().contains("DownloadProgress")) {
                                View downloadBtn = item;
                                // 诊断日志：输出按钮状态
                                int vis = downloadBtn.getVisibility();
                                int w = downloadBtn.getWidth();
                                int h = downloadBtn.getHeight();
                                ViewGroup.LayoutParams lp = downloadBtn.getLayoutParams();
                                info("SelectAll: downloadBtn vis=" + vis + " w=" + w + " h=" + h + " lp=" + (lp != null ? lp.getClass().getSimpleName() : "null"));
                                if (lp != null) {
                                    info("SelectAll: lp.width=" + lp.width + " lp.height=" + lp.height);
                                }
                                // 检查父容器
                                ViewParent parent = downloadBtn.getParent();
                                if (parent instanceof ViewGroup) {
                                    ViewGroup parentVg = (ViewGroup) parent;
                                    int parentVis = parentVg.getVisibility();
                                    int parentW = parentVg.getWidth();
                                    int parentH = parentVg.getHeight();
                                    info("SelectAll: parent vis=" + parentVis + " w=" + parentW + " h=" + parentH + " class=" + parentVg.getClass().getSimpleName());
                                }
                                // 强制设置尺寸并显示
                                downloadBtn.setVisibility(View.VISIBLE);
                                if (lp != null && (lp.width <= 0 || lp.height <= 0)) {
                                    int size = dp(downloadBtn.getContext(), 48);
                                    lp.width = size;
                                    lp.height = size;
                                    downloadBtn.setLayoutParams(lp);
                                    info("SelectAll: forced size to " + size);
                                }
                                downloadBtn.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                                    if (v.getVisibility() != View.VISIBLE) {
                                        info("SelectAll: download button hidden! Setting back to VISIBLE");
                                        v.setVisibility(View.VISIBLE);
                                    }
                                });
                                info("SelectAll: attached layout listener and set VISIBLE");
                                return;
                            }
                        }
                    }
                }
            }
        }
    }

    private void dumpTextViews(ViewGroup group, String label, int depth, int maxDepth) {
        if (depth > maxDepth) return;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof TextView) {
                String text = ((TextView) child).getText().toString().trim();
                if (!text.isEmpty()) {
                    info("SelectAll: " + label + "[" + depth + "][" + i + "] text=\"" + text + "\"");
                }
            }
            if (child instanceof ViewGroup) {
                dumpTextViews((ViewGroup) child, label, depth + 1, maxDepth);
            }
        }
    }

    private void dumpViewTree(View view, int depth, int maxDepth) {
        if (depth > maxDepth) return;
        String name = view.getClass().getSimpleName();
        String extra = "";
        if (view instanceof TextView) extra = " text=\"" + ((TextView) view).getText() + "\"";
        info("SelectAll: " + spaces(depth) + name + extra + " vis=" + view.getVisibility() + " w=" + view.getWidth() + " h=" + view.getHeight());
        if (view instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) view;
            for (int i = 0; i < vg.getChildCount(); i++) {
                dumpViewTree(vg.getChildAt(i), depth + 1, maxDepth);
            }
        }
    }

    private String spaces(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append("  ");
        return sb.toString();
    }

    private void dumpMenuItems(ViewGroup menu) {
        info("SelectAll: ActionBarMenu children=" + menu.getChildCount());
        for (int i = 0; i < menu.getChildCount(); i++) {
            View child = menu.getChildAt(i);
            String name = child.getClass().getSimpleName();
            String text = "";
            if (child instanceof TextView) text = " text=\"" + ((TextView) child).getText() + "\"";
            int id = child.getId();
            Object tag = child.getTag();
            info("SelectAll: menu[" + i + "]=" + name + text + " vis=" + child.getVisibility() + " id=" + id + " tag=" + tag);
            if (child instanceof ViewGroup) {
                ViewGroup vg = (ViewGroup) child;
                for (int j = 0; j < vg.getChildCount(); j++) {
                    View sub = vg.getChildAt(j);
                    String subName = sub.getClass().getSimpleName();
                    String subText = "";
                    if (sub instanceof TextView) subText = " text=\"" + ((TextView) sub).getText() + "\"";
                    info("SelectAll:   [" + j + "]=" + subName + subText + " vis=" + sub.getVisibility());
                }
            }
        }
    }

    private void injectSelectAllIntoContentView(View actionBar) {
        if (!(actionBar instanceof ViewGroup)) return;
        ViewGroup bar = (ViewGroup) actionBar;
        if (bar.findViewWithTag(MENU_ID_SELECT_ALL) != null) return;
        if (bar.getChildCount() < 5) return;
        View contentView = bar.getChildAt(4);
        if (!(contentView instanceof ViewGroup)) return;
        ViewGroup content = (ViewGroup) contentView;
        info("SelectAll: ActionBar content children=" + content.getChildCount());
        int xIndex = -1;
        for (int i = 0; i < content.getChildCount(); i++) {
            View c = content.getChildAt(i);
            String txt = "";
            if (c instanceof TextView) txt = " text=\"" + ((TextView) c).getText() + "\"";
            info("SelectAll:   [" + i + "]=" + c.getClass().getSimpleName() + txt + " vis=" + c.getVisibility() + " w=" + c.getWidth());
            if (c instanceof android.widget.ImageButton || (c.getClass().getSimpleName().contains("Item") && c.getWidth() < 200 && c.getWidth() > 0)) {
                xIndex = i;
            }
        }
        Context context = content.getContext();
        CharSequence label = isChineseLocale(context) ? "全选" : "Select All";
        TextView button = new TextView(context);
        button.setTag(MENU_ID_SELECT_ALL);
        button.setText(label);
        button.setTextSize(14);
        button.setTextColor(0xFFFFFFFF);
        button.setPadding(dp(context, 16), 0, dp(context, 16), 0);
        button.setGravity(android.view.Gravity.CENTER);
        ViewGroup.LayoutParams lp = new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
        int insertIdx = 2;
        for (int i = 0; i < content.getChildCount(); i++) {
            View c = content.getChildAt(i);
            if (c.getClass().getSimpleName().contains("Number") || (c instanceof TextView && c.getWidth() > 200)) {
                insertIdx = i + 1;
                break;
            }
        }
        content.addView(button, insertIdx, lp);
        info("SelectAll: inserted at index " + insertIdx);
        ViewGroup parent = bar.getParent() instanceof ViewGroup ? (ViewGroup) bar.getParent() : null;
        button.setOnClickListener(v -> {
            try {
                info("SelectAll: clicked!");
                if (parent != null) selectAllFromContentView(parent);
            } catch (Throwable t) {
                error("Select all failed", t);
            }
        });
    }

    private View findViewByText(ViewGroup group, String text) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof TextView && ((TextView) child).getText().toString().contains(text)) {
                return child;
            }
            if (child instanceof ViewGroup) {
                View found = findViewByText((ViewGroup) child, text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private boolean hasTextViewWith(View view, String targetText) {
        if (view instanceof TextView) {
            return ((TextView) view).getText().toString().contains(targetText);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (hasTextViewWith(group.getChildAt(i), targetText)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void selectAllFromContentView(ViewGroup contentView) {
        View listView = findListViewDeep(contentView, 0);
        if (!(listView instanceof ViewGroup)) {
            info("SelectAll: no list view found");
            return;
        }
        ViewGroup rv = (ViewGroup) listView;
        Object longClickListener = Reflect.field(rv, "mOnItemLongClickListener");
        if (longClickListener == null) {
            Object adapter = Reflect.invokeIfExists(rv, "getAdapter", new Class<?>[0]);
            Object hostFragment = adapter != null ? Reflect.field(adapter, "this$0") : null;
            if (hostFragment != null) {
                longClickListener = hostFragment;
            }
        }
        if (longClickListener == null) {
            info("SelectAll: no longClickListener");
            return;
        }
        Object adapter = Reflect.invokeIfExists(rv, "getAdapter", new Class<?>[0]);
        if (adapter == null) {
            info("SelectAll: no adapter");
            return;
        }
        Object itemInternals = Reflect.field(adapter, "itemInternals");
        if (!(itemInternals instanceof java.util.ArrayList)) {
            info("SelectAll: no itemInternals");
            return;
        }
        @SuppressWarnings("unchecked")
        java.util.ArrayList<?> items = (java.util.ArrayList<?>) itemInternals;
        int selected = 0;
        for (int i = 0; i < items.size(); i++) {
            Object item = items.get(i);
            if (item == null) continue;
            Object dialog = Reflect.field(item, "dialog");
            if (dialog == null) continue;
            long dialogId = Reflect.asLong(Reflect.field(dialog, "id"), 0L);
            if (dialogId == 0) continue;
            View childView = rv.getChildCount() > 0 ? rv.getChildAt(0) : null;
            Reflect.invokeIfExists(longClickListener, "addOrRemoveSelectedDialog", new Class<?>[]{long.class, View.class}, dialogId, childView);
            selected++;
        }
        View firstChild = rv.getChildCount() > 0 ? rv.getChildAt(0) : null;
        if (firstChild == null) return;
        Object holder = Reflect.invokeIfExists(rv, "getChildViewHolder", new Class<?>[]{View.class}, firstChild);
        int pos = holder != null ? Reflect.asInt(Reflect.invokeIfExists(holder, "getLayoutPosition", new Class<?>[0]), 0) : 0;
        Object result = null;
        try {
            Class<?> rlvClass = Class.forName("org.telegram.ui.Components.RecyclerListView");
            result = Reflect.invokeIfExists(longClickListener, "onItemLongClick",
                    new Class<?>[]{rlvClass, View.class, int.class, float.class, float.class, int.class, androidx.recyclerview.widget.RecyclerView.Adapter.class},
                    rv, firstChild, pos, 0f, 0f, 0, adapter);
        } catch (ClassNotFoundException ignored) {}
        info("SelectAll: onItemLongClick result=" + result);
        Object selectedDialogs = Reflect.field(adapter, "selectedDialogs");
        if (selectedDialogs instanceof java.util.ArrayList) {
            @SuppressWarnings("unchecked")
            java.util.ArrayList<Long> list = (java.util.ArrayList<Long>) selectedDialogs;
            for (int i = 1; i < items.size(); i++) {
                Object item = items.get(i);
                if (item == null) continue;
                Object dialog = Reflect.field(item, "dialog");
                if (dialog == null) continue;
                long dialogId = Reflect.asLong(Reflect.field(dialog, "id"), 0L);
                if (dialogId != 0 && !list.contains(dialogId)) {
                    list.add(dialogId);
                }
            }
            Reflect.invokeIfExists(longClickListener, "updateSelectedCount", new Class<?>[0]);
            info("SelectAll: selectedDialogs.size=" + list.size());
        }
    }

    private void dumpViewHierarchy(ViewGroup group, int depth, int maxDepth) {
        if (depth > maxDepth) return;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            String name = child.getClass().getSimpleName();
            if (child instanceof ViewGroup && ((ViewGroup) child).getChildCount() > 3) {
                info("SelectAll: d=" + depth + " i=" + i + " " + name + " children=" + ((ViewGroup) child).getChildCount() + " h=" + child.getHeight());
            }
            if (child instanceof ViewGroup) {
                dumpViewHierarchy((ViewGroup) child, depth + 1, maxDepth);
            }
        }
    }

    private View findListViewDeep(ViewGroup group, int depth) {
        if (depth > 12) return null;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            String name = child.getClass().getSimpleName();
            if (name.contains("Recycler") || name.contains("ListView") || name.contains("yclerList")) {
                info("SelectAll: d=" + depth + " found " + name + " children=" + (child instanceof ViewGroup ? ((ViewGroup) child).getChildCount() : 0));
                return child;
            }
            if (child instanceof ViewGroup) {
                View found = findListViewDeep((ViewGroup) child, depth + 1);
                if (found != null) return found;
            }
        }
        return null;
    }

    private View findViewByClassName(ViewGroup group, String nameSuffix, int depth) {
        if (depth > 10) return null;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child.getClass().getSimpleName().contains(nameSuffix)) {
                return child;
            }
            if (child instanceof ViewGroup) {
                View found = findViewByClassName((ViewGroup) child, nameSuffix, depth + 1);
                if (found != null) return found;
            }
        }
        return null;
    }

    private void injectSelectAllMenu(Object activity) {
        info("SelectAll: entering injectSelectAllMenu for " + activity.getClass().getSimpleName());
        Object actionBar = Reflect.field(activity, "actionBar");
        info("SelectAll: actionBar=" + (actionBar == null ? "null" : actionBar.getClass().getName()));
        if (!(actionBar instanceof ViewGroup)) {
            return;
        }
        ViewGroup bar = (ViewGroup) actionBar;
        if (bar.findViewWithTag(MENU_ID_SELECT_ALL) != null) {
            return;
        }
        Context context = bar.getContext();
        int selectAllIcon = context.getResources().getIdentifier("msg_select_all", "drawable", context.getPackageName());
        if (selectAllIcon == 0) {
            selectAllIcon = android.R.drawable.ic_menu_agenda;
        }
        TextView button = new TextView(context);
        button.setTag(MENU_ID_SELECT_ALL);
        button.setText(isChineseLocale(context) ? "全选" : "Select All");
        button.setTextSize(14);
        button.setPadding(dp(context, 12), 0, dp(context, 12), 0);
        button.setGravity(android.view.Gravity.CENTER);
        button.setOnClickListener(v -> {
            try {
                selectAllDownloadItems(activity);
            } catch (Throwable throwable) {
                error("Select all failed", throwable);
            }
        });
        bar.addView(button, 0);
        info("SelectAll: injected button into " + activity.getClass().getSimpleName() + " actionBar");
    }

    private void injectSelectAllIntoActionMode(Object menu) {
        if (!(menu instanceof ViewGroup)) {
            return;
        }
        ViewGroup menuView = (ViewGroup) menu;
        if (menuView.findViewWithTag(MENU_ID_SELECT_ALL) != null) {
            return;
        }
        Context context = menuView.getContext();
        CharSequence label = isChineseLocale(context) ? "全选" : "Select All";
        TextView button = new TextView(context);
        button.setTag(MENU_ID_SELECT_ALL);
        button.setText(label);
        button.setTextSize(14);
        button.setTextColor(0xFFFFFFFF);
        button.setPadding(dp(context, 16), 0, dp(context, 16), 0);
        button.setGravity(android.view.Gravity.CENTER);
        button.setOnClickListener(v -> {
            try {
                selectAllInActionMode(menuView);
            } catch (Throwable throwable) {
                error("Select all in action mode failed", throwable);
            }
        });
        menuView.addView(button);
        info("SelectAll: injected into action mode bar");
    }

    /**
     * Injects "Select All" button into the action mode bar for the download page.
     * When clicked, it selects all download items.
     */
    private void injectSelectAllIntoActionModeForDownload(ViewGroup menuView, ViewGroup fragmentView) {
        if (menuView.findViewWithTag(MENU_ID_SELECT_ALL) != null) {
            return;
        }
        Context context = menuView.getContext();
        CharSequence label = isChineseLocale(context) ? "全选" : "Select All";
        TextView button = new TextView(context);
        button.setTag(MENU_ID_SELECT_ALL);
        button.setText(label);
        button.setTextSize(14);
        button.setTextColor(0xFFFFFFFF);
        button.setPadding(dp(context, 16), 0, dp(context, 16), 0);
        button.setGravity(android.view.Gravity.CENTER);
        // No background - match the delete button style
        ViewGroup.LayoutParams lp = new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
        button.setOnClickListener(v -> {
            try {
                info("SelectAll: download page Select All clicked in action mode");
                selectAllDownloadItems(fragmentView);
            } catch (Throwable t) {
                error("SelectAll: download select all failed: " + t.getMessage(), t);
            }
        });
        menuView.addView(button, lp);
        info("SelectAll: injected into action mode bar for download page");
    }

    private void selectAllInActionMode(ViewGroup menuView) {
        // Walk up from the ActionBarMenu to find the chat RecyclerView
        Object parent = menuView.getParent();
        RecyclerView recyclerView = null;
        while (parent instanceof View) {
            if (parent instanceof ViewGroup) {
                recyclerView = findRecyclerView((ViewGroup) parent);
                if (recyclerView != null) {
                    break;
                }
            }
            parent = ((View) parent).getParent();
        }
        if (recyclerView == null) {
            info("SelectAll: RecyclerView not found in action mode");
            return;
        }

        // Get adapter to access ChatActivity
        RecyclerView.Adapter<?> adapter = recyclerView.getAdapter();
        if (adapter == null) {
            info("SelectAll: adapter is null");
            return;
        }
        info("SelectAll: adapter class=" + adapter.getClass().getSimpleName());

        // Find ChatActivity instance via adapter.this$0
        Object chatActivity = Reflect.field(adapter, "this$0");
        if (chatActivity == null) {
            info("SelectAll: chatActivity not found via this$0, trying other fields");
            chatActivity = Reflect.field(adapter, "fragment");
        }
        if (chatActivity == null) {
            info("SelectAll: chatActivity not found, dumping adapter fields");
            dumpAllFieldsDeep(adapter, "adapter", 1);
            fallbackSelectAll(recyclerView);
            return;
        }
        info("SelectAll: chatActivity class=" + chatActivity.getClass().getSimpleName());

        // Find the messages list on ChatActivity
        Object messagesObj = Reflect.field(chatActivity, "messages");
        if (!(messagesObj instanceof java.util.ArrayList)) {
            info("SelectAll: messages field not found, dumping chatActivity fields");
            dumpAllFieldsDeep(chatActivity, "chatActivity", 1);
            fallbackSelectAll(recyclerView);
            return;
        }

        // Find selectedIds on ChatActivity
        Object selectedIdsObj = findSelectionField(chatActivity);
        if (!(selectedIdsObj instanceof java.util.ArrayList)) {
            info("SelectAll: selectedIds not found as ArrayList, dumping collection fields");
            dumpCollectionFields(chatActivity, "chatActivity");
            dumpAllFieldsDeep(chatActivity, "chatActivity", 1);
            fallbackSelectAll(recyclerView);
            return;
        }

        @SuppressWarnings("unchecked")
        java.util.ArrayList<Integer> selectedIds = (java.util.ArrayList<Integer>) selectedIdsObj;
        @SuppressWarnings("unchecked")
        java.util.ArrayList<?> messages = (java.util.ArrayList<?>) messagesObj;
        int initialSize = selectedIds.size();

        // Add all message IDs to selectedIds
        int added = 0;
        for (Object msg : messages) {
            if (msg == null) continue;
            Object idObj = Reflect.invokeIfExists(msg, "getId", new Class<?>[0]);
            int id = idObj instanceof Integer ? (Integer) idObj : 0;
            if (id > 0 && !selectedIds.contains(id)) {
                selectedIds.add(id);
                added++;
            }
        }
        info("SelectAll: added " + added + " IDs, selectedIds " + initialSize + " -> " + selectedIds.size());

        // Update the action bar counter
        updateActionBarSelectionCount(chatActivity, selectedIds.size());

        // Refresh adapter to sync visual check state with data model
        adapter.notifyDataSetChanged();

        // Force re-bind visible cells to update checkmarks
        final RecyclerView rv = recyclerView;
        rv.post(() -> {
            for (int i = 0; i < rv.getChildCount(); i++) {
                View child = rv.getChildAt(i);
                if (child != null) {
                    child.invalidate();
                }
            }
        });
    }

    /**
     * Finds the selection tracking field (selectedIds, etc.) on ChatActivity.
     */
    private Object findSelectionField(Object chatActivity) {
        String[] fieldNames = {"selectedIds", "selectedMessagesIds", "selectedMessages", "selectedObjectIds"};
        for (String name : fieldNames) {
            Object field = Reflect.field(chatActivity, name);
            if (field instanceof java.util.ArrayList) {
                info("SelectAll: found selection field '" + name + "' size=" + ((java.util.ArrayList<?>) field).size());
                return field;
            }
        }
        // Broader search: find any ArrayList<Integer> field on ChatActivity
        Class<?> clazz = chatActivity.getClass();
        while (clazz != null && clazz != Object.class) {
            for (java.lang.reflect.Field field : clazz.getDeclaredFields()) {
                if (java.util.ArrayList.class.isAssignableFrom(field.getType())) {
                    try {
                        field.setAccessible(true);
                        Object value = field.get(chatActivity);
                        if (value instanceof java.util.ArrayList) {
                            java.util.ArrayList<?> list = (java.util.ArrayList<?>) value;
                            if (!list.isEmpty() && list.get(0) instanceof Integer) {
                                info("SelectAll: found ArrayList<Integer> field '" + field.getName() + "' size=" + list.size());
                                return value;
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            }
            clazz = clazz.getSuperclass();
        }
        return null;
    }

    /**
     * Updates the action bar selection count display.
     */
    private void updateActionBarSelectionCount(Object chatActivity, int count) {
        // Try updateSelectedCount() with no args
        Object result = Reflect.invokeIfExists(chatActivity, "updateSelectedCount", new Class<?>[0]);
        if (result != null) {
            info("SelectAll: updateSelectedCount() succeeded");
            return;
        }
        // Try updateSelectedCount(int)
        result = Reflect.invokeIfExists(chatActivity, "updateSelectedCount", new Class<?>[]{int.class}, count);
        if (result != null) {
            info("SelectAll: updateSelectedCount(int) succeeded");
            return;
        }
        // Try showOrUpdateActionMode on ChatActivity
        result = Reflect.invokeIfExists(chatActivity, "showOrUpdateActionMode", new Class<?>[0]);
        if (result != null) {
            info("SelectAll: showOrUpdateActionMode() succeeded");
            return;
        }
        // Try actionBar.setSubTitle with count
        Object actionBar = Reflect.field(chatActivity, "actionBar");
        if (actionBar instanceof ViewGroup) {
            result = Reflect.invokeIfExists(actionBar, "setSubTitle",
                    new Class<?>[]{CharSequence.class}, String.valueOf(count));
            if (result != null) {
                info("SelectAll: actionBar.setSubTitle succeeded with count=" + count);
                return;
            }
        }
        info("SelectAll: WARNING - could not update action bar counter");
    }

    /**
     * Fallback: try to select all via performLongClick on visible children.
     */
    private void fallbackSelectAll(RecyclerView recyclerView) {
        info("SelectAll: using performLongClick fallback on " + recyclerView.getChildCount() + " children");
        for (int i = 0; i < recyclerView.getChildCount(); i++) {
            View child = recyclerView.getChildAt(i);
            if (child != null) {
                child.performLongClick();
            }
        }
    }

    private void injectSelectAllIntoActionBar(Object actionBar) {
        if (!(actionBar instanceof ViewGroup)) {
            return;
        }
        ViewGroup bar = (ViewGroup) actionBar;
        if (bar.findViewWithTag(MENU_ID_SELECT_ALL) != null) {
            return;
        }
        Context context = bar.getContext();
        CharSequence label = isChineseLocale(context) ? "全选" : "Select All";
        TextView button = new TextView(context);
        button.setTag(MENU_ID_SELECT_ALL);
        button.setText(label);
        button.setTextSize(14);
        button.setTextColor(0xFFFFFFFF);
        button.setPadding(dp(context, 12), 0, dp(context, 12), 0);
        button.setGravity(android.view.Gravity.CENTER);
        button.setBackgroundColor(0x33FFFFFF);
        ViewGroup.LayoutParams lp = new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
        int childCount = bar.getChildCount();
        for (int i = 0; i < childCount; i++) {
            View child = bar.getChildAt(i);
            if (child instanceof ViewGroup && child.getVisibility() == View.VISIBLE && child.getWidth() > 100) {
                ViewGroup content = (ViewGroup) child;
                int insertIndex = content.getChildCount() > 0 ? content.getChildCount() : 0;
                content.addView(button, insertIndex, lp);
                button.bringToFront();
                content.invalidate();
                content.requestLayout();
                info("SelectAll: added to child[" + i + "] at " + insertIndex + " broughtToFront");
                return;
            }
        }
        bar.addView(button, lp);
        button.bringToFront();
        button.setClickable(true);
        button.setFocusable(true);
        button.setOnClickListener(v -> {
            try {
                info("SelectAll: button clicked!");
                selectAllFromActionBar(bar);
            } catch (Throwable throwable) {
                error("Select all failed", throwable);
            }
        });
        button.setOnTouchListener((v, event) -> {
            info("SelectAll: touch event=" + event.getAction());
            return false;
        });
    }

    private View findCountTextView(ViewGroup group) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof TextView) {
                String text = ((TextView) child).getText().toString().trim();
                if (text.matches("\\d+") && child.getVisibility() == View.VISIBLE) {
                    return child;
                }
            }
            if (child instanceof ViewGroup) {
                View found = findCountTextView((ViewGroup) child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private int findCountViewIndex(ViewGroup group) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof TextView) {
                String text = ((TextView) child).getText().toString().trim();
                if (text.matches("\\d+.*")) {
                    return i;
                }
            }
        }
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof ViewGroup) {
                int found = findCountViewIndex((ViewGroup) child);
                if (found >= 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private void selectAllFromActionBar(ViewGroup bar) {
        ViewGroup parent = bar.getParent() instanceof ViewGroup ? (ViewGroup) bar.getParent() : null;
        if (parent == null) {
            info("SelectAll: no parent");
            return;
        }
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child != bar && child instanceof ViewGroup) {
                RecyclerView rv = findRecyclerView((ViewGroup) child);
                if (rv != null) {
                    selectAllByLongClick(rv);
                    return;
                }
            }
        }
        ViewGroup grandParent = parent.getParent() instanceof ViewGroup ? (ViewGroup) parent.getParent() : null;
        if (grandParent != null) {
            for (int i = 0; i < grandParent.getChildCount(); i++) {
                View child = grandParent.getChildAt(i);
                if (child != parent && child instanceof ViewGroup) {
                    RecyclerView rv = findRecyclerView((ViewGroup) child);
                    if (rv != null) {
                        selectAllByLongClick(rv);
                        return;
                    }
                }
            }
        }
        info("SelectAll: RecyclerView not found among siblings");
    }

    private void selectAllInActionBar(ViewGroup bar) {
        View current = bar;
        for (int depth = 0; depth < 10; depth++) {
            View parent = (View) current.getParent();
            if (!(parent instanceof ViewGroup)) {
                break;
            }
            RecyclerView recyclerView = findRecyclerView((ViewGroup) parent);
            if (recyclerView != null) {
                info("SelectAll: found RecyclerView at depth " + depth + " in " + parent.getClass().getSimpleName());
                selectAllByLongClick(recyclerView);
                return;
            }
            current = parent;
        }
        info("SelectAll: RecyclerView not found");
    }

    private void selectAllByLongClick(ViewGroup listView) {
        info("SelectAll: selectAllByLongClick view=" + listView.getClass().getName());
        Object adapter = Reflect.invokeIfExists(listView, "getAdapter", new Class<?>[0]);
        if (adapter != null) {
            info("SelectAll: adapter class=" + adapter.getClass().getName());
            Class<?> clazz = adapter.getClass();
            while (clazz != null && clazz != Object.class) {
                for (java.lang.reflect.Field f : clazz.getDeclaredFields()) {
                    if (java.util.Set.class.isAssignableFrom(f.getType()) || java.util.List.class.isAssignableFrom(f.getType())
                            || android.util.LongSparseArray.class.isAssignableFrom(f.getType())
                            || android.util.SparseArray.class.isAssignableFrom(f.getType())) {
                        f.setAccessible(true);
                        info("SelectAll: field " + f.getName() + " type=" + f.getType().getSimpleName());
                    }
                }
                clazz = clazz.getSuperclass();
            }
            Object selectedField = Reflect.field(adapter, "selectedDialogs");
            if (selectedField == null) selectedField = Reflect.field(adapter, "selectedIds");
            if (selectedField == null) selectedField = Reflect.field(adapter, "selectedFiles");
            if (selectedField == null) selectedField = Reflect.field(adapter, "selectedDocuments");
            info("SelectAll: selectedField=" + (selectedField == null ? "null" : selectedField.getClass().getSimpleName()));
            if (selectedField instanceof java.util.Collection) {
                @SuppressWarnings("unchecked")
                java.util.Collection<Object> collection = (java.util.Collection<Object>) selectedField;
                info("SelectAll: selectedDialogs size=" + collection.size());
                Object hostFragment = Reflect.field(adapter, "this$0");
                info("SelectAll: hostFragment=" + (hostFragment == null ? "null" : hostFragment.getClass().getSimpleName()));
                Object itemInternals = Reflect.field(adapter, "itemInternals");
                if (itemInternals instanceof java.util.ArrayList) {
                    @SuppressWarnings("unchecked")
                    java.util.ArrayList<?> items = (java.util.ArrayList<?>) itemInternals;
                    info("SelectAll: itemInternals size=" + items.size());
                    int added = 0;
                    for (Object item : items) {
                        if (item == null) continue;
                        Object dialog = Reflect.field(item, "dialog");
                        if (dialog == null) continue;
                        long dialogId = Reflect.asLong(Reflect.field(dialog, "id"), 0L);
                        if (dialogId != 0) {
                            collection.add(dialogId);
                            added++;
                        }
                    }
                    if (added > 0) {
                        for (Object item : items) {
                            if (item == null) continue;
                            Object dialog = Reflect.field(item, "dialog");
                            if (dialog == null) continue;
                            long dialogId = Reflect.asLong(Reflect.field(dialog, "id"), 0L);
                            if (dialogId != 0) {
                                Reflect.invokeIfExists(hostFragment, "onItemLongClick", new Class<?>[]{long.class}, dialogId);
                            }
                        }
                        info("SelectAll: toggled " + added + " items via onItemLongClick");
                        return;
                    }
                    info("SelectAll: no dialogs found in items");
                }
                info("SelectAll: no items found, fallback");
            }
        }
        int count = 0;
        for (int i = 0; i < listView.getChildCount(); i++) {
            View child = listView.getChildAt(i);
            if (child != null) {
                child.performLongClick();
                count++;
            }
        }
        info("SelectAll: fallback long-click on " + count + " items");
    }

    private void selectAllDownloadItems(Object activity) {
        View fragmentView = resolveFragmentView(activity);
        if (!(fragmentView instanceof ViewGroup)) {
            return;
        }
        RecyclerView recyclerView = findRecyclerView((ViewGroup) fragmentView);
        if (recyclerView == null) {
            return;
        }
        RecyclerView.Adapter<?> adapter = recyclerView.getAdapter();
        if (adapter == null) {
            return;
        }
        boolean selected = tryAdapterSelectAll(adapter);
        if (selected) {
            adapter.notifyDataSetChanged();
            return;
        }
        fallbackClickSelectAll(recyclerView);
    }

    @SuppressWarnings("unchecked")
    private boolean tryAdapterSelectAll(RecyclerView.Adapter<?> adapter) {
        Object selectedField = Reflect.field(adapter, "selectedIds");
        if (selectedField == null) {
            selectedField = Reflect.field(adapter, "selectedFiles");
        }
        if (selectedField == null) {
            selectedField = Reflect.field(adapter, "selectedMessages");
        }
        if (selectedField instanceof java.util.Set) {
            java.util.Set<Number> set = (java.util.Set<Number>) selectedField;
            int itemCount = adapter.getItemCount();
            for (int i = 0; i < itemCount; i++) {
                long id = adapter.getItemId(i);
                if (id != RecyclerView.NO_ID) {
                    set.add(id);
                }
            }
            return true;
        }
        if (selectedField instanceof android.util.LongSparseArray) {
            @SuppressWarnings("unchecked")
            android.util.LongSparseArray<Object> array = (android.util.LongSparseArray<Object>) selectedField;
            int itemCount = adapter.getItemCount();
            for (int i = 0; i < itemCount; i++) {
                long id = adapter.getItemId(i);
                if (id != RecyclerView.NO_ID) {
                    array.put(id, Boolean.TRUE);
                }
            }
            return true;
        }
        return false;
    }

    private void fallbackClickSelectAll(RecyclerView recyclerView) {
        for (int i = 0; i < recyclerView.getChildCount(); i++) {
            View child = recyclerView.getChildAt(i);
            if (child != null) {
                child.performLongClick();
            }
        }
    }

    private View resolveFragmentView(Object activity) {
        Object view = Reflect.field(activity, "fragmentView");
        if (view instanceof View) {
            return (View) view;
        }
        return Reflect.invokeIfExists(activity, "getFragmentView", new Class<?>[0]) instanceof View
                ? (View) Reflect.invokeIfExists(activity, "getFragmentView", new Class<?>[0])
                : null;
    }

    private RecyclerView findRecyclerView(ViewGroup group) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof RecyclerView) {
                return (RecyclerView) child;
            }
            if (child instanceof ViewGroup) {
                RecyclerView found = findRecyclerView((ViewGroup) child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private Object findActionBarOverflow(ViewGroup actionBar) {
        for (int i = actionBar.getChildCount() - 1; i >= 0; i--) {
            View child = actionBar.getChildAt(i);
            if (child.getClass().getName().contains("ActionBarMenuItem")) {
                return child;
            }
            if (child instanceof ViewGroup) {
                Object found = findActionBarMenuItemRecursive((ViewGroup) child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private Object findActionBarMenuItemRecursive(ViewGroup group) {
        for (int i = group.getChildCount() - 1; i >= 0; i--) {
            View child = group.getChildAt(i);
            if (child.getClass().getName().contains("ActionBarMenuItem")) {
                return child;
            }
            if (child instanceof ViewGroup) {
                Object found = findActionBarMenuItemRecursive((ViewGroup) child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private void hookSettingsActivityMenu(ClassLoader classLoader) {
        try {
            Class<?> settingsActivityClass = classLoader.loadClass("org.telegram.ui.SettingsActivity");
            hookSettingsActivityListRow(classLoader, settingsActivityClass);
            hookSettingsActivityBack(settingsActivityClass);
            info("Hooked SettingsActivity host entry");
        } catch (Throwable throwable) {
            error("Failed to hook SettingsActivity host entry", throwable);
        }
    }

    private void hookSettingsActivityListRow(ClassLoader classLoader, Class<?> settingsActivityClass) {
        try {
            Class<?> universalAdapterClass = classLoader.loadClass("org.telegram.ui.Components.UniversalAdapter");
            Class<?> uItemClass = classLoader.loadClass("org.telegram.ui.Components.UItem");
            Class<?> settingCellFactoryClass = classLoader.loadClass("org.telegram.ui.SettingsActivity$SettingCell$Factory");
            Method factoryOf = Reflect.method(
                    settingCellFactoryClass,
                    "of",
                    int.class,
                    int.class,
                    int.class,
                    int.class,
                    CharSequence.class,
                    CharSequence.class
            );
            Method fillItems = Reflect.method(settingsActivityClass, "fillItems", ArrayList.class, universalAdapterClass);
            hook(fillItems, chain -> {
                Object result = chain.proceed();
                try {
                    Object arg = chain.getArg(0);
                    if (arg instanceof ArrayList<?>) {
                        @SuppressWarnings("unchecked")
                        ArrayList<Object> items = (ArrayList<Object>) arg;
                        injectSettingsListRow(chain.getThisObject(), items, factoryOf);
                    }
                } catch (Throwable throwable) {
                    error("SettingsActivity row injection failed", throwable);
                }
                return result;
            });

            Method onClick = Reflect.method(settingsActivityClass, "onClick", uItemClass, View.class, int.class, float.class, float.class);
            hook(onClick, chain -> {
                Object item = chain.getArg(0);
                if (!isGramSieveSettingsItem(item)) {
                    return chain.proceed();
                }
                Context context = contextFromSettingsClick(chain.getThisObject(), chain.getArg(1));
                if (context == null) {
                    info("SettingsActivity row click ignored: context unavailable");
                    return null;
                }
                openConfigFromHost(chain.getThisObject(), context, CONFIG_MODE_GLOBAL, 0L, "");
                return null;
            });
            info("Hooked SettingsActivity list row");
        } catch (Throwable throwable) {
            error("Failed to hook SettingsActivity list row", throwable);
        }
    }

    private void hookSettingsActivityBack(Class<?> settingsActivityClass) {
        try {
            Method onBackPressed = Reflect.method(settingsActivityClass, "onBackPressed", boolean.class);
            hook(onBackPressed, chain -> {
                try {
                    if (closeHostConfigPanel(chain.getThisObject())) {
                        return false;
                    }
                } catch (Throwable throwable) {
                    error("SettingsActivity host panel back handling failed", throwable);
                }
                return chain.proceed();
            });
            info("Hooked SettingsActivity back for host panel");
        } catch (Throwable throwable) {
            error("Failed to hook SettingsActivity back", throwable);
        }
    }

    private static volatile int bindingCallCount = 0;

    private Object handleMessageBinding(XposedInterface.Chain chain) throws Throwable {
        int count = ++bindingCallCount;
        if (count <= 3) {
            info("handleMessageBinding #" + count);
        }
        Object result = chain.proceed();
        try {
            Object cell = chain.getThisObject();
            Object messageObject = chain.getArg(0);
            if (cell instanceof View && messageObject != null) {
                View cellView = (View) cell;
                clearAntiRecallCellVisualState(cellView);
                applyDecision(cellView, cellView, messageObject);
                cacheAndApplyAntiRecall(cellView, messageObject);
            }
        } catch (Throwable throwable) {
            error("Message filtering failed", throwable);
        }
        return result;
    }

    private static volatile int cacheCallCount = 0;

    private void cacheAndApplyAntiRecall(View cell, Object messageObject) {
        int count = ++cacheCallCount;
        if (messageObject == null || messageCache == null || backgroundMessageLoader == null || mediaCache == null) return;
        try {
            long dialogId = Reflect.asLong(Reflect.invokeIfExists(messageObject, "getDialogId", new Class<?>[0]), 0L);
            long messageId = Reflect.asLong(Reflect.invokeIfExists(messageObject, "getId", new Class<?>[0]), 0L);
            if (dialogId == 0L || messageId == 0L) return;
            boolean enabled = backgroundMessageLoader.isChatEnabled(dialogId);
            if (count <= 10 || count % 200 == 0) {
                info("cacheAndApply #" + count + " dialogId=" + dialogId + " msgId=" + messageId + " enabled=" + enabled);
            }
            if (!enabled) return;

            Object owner = Reflect.field(messageObject, "messageOwner");
            String fullContent = "";
            String caption = "";
            String mediaType = null;
            String mediaId = null;
            String cachedMediaPath = null;
            Object mediaAttachment = null;
            String mediaKind = null;
            Object media = null;
            if (owner != null) {
                String text = Reflect.asString(Reflect.field(owner, "message"));
                media = Reflect.field(owner, "media");
                if (media != null) {
                    caption = Reflect.asString(Reflect.field(media, "caption"));
                    mediaType = media.getClass().getSimpleName();
                    // Try to get photo ID or video ID
                    Object photo = Reflect.field(media, "photo");
                    if (photo != null) {
                        mediaId = String.valueOf(Reflect.asLong(Reflect.field(photo, "id"), 0L));
                        mediaAttachment = photo;
                        mediaKind = "photo";
                    } else {
                        Object video = Reflect.field(media, "video");
                        if (video != null) {
                            mediaId = String.valueOf(Reflect.asLong(Reflect.field(video, "id"), 0L));
                            mediaAttachment = video;
                            mediaKind = "video";
                        } else {
                            Object document = Reflect.field(media, "document");
                            if (document != null) {
                                mediaId = String.valueOf(Reflect.asLong(Reflect.field(document, "id"), 0L));
                                mediaAttachment = document;
                                mediaKind = "document";
                            }
                        }
                    }
                }
                fullContent = text != null ? text : "";
                if (caption != null && !caption.isEmpty()) {
                    fullContent = fullContent.isEmpty() ? caption : fullContent + "\n" + caption;
                }
            }

            MessageCache.CachedMessage existing = messageCache.get(dialogId, messageId);

            // Build a content fingerprint for comparison (text + mediaType + mediaId)
            String contentFingerprint = fullContent + "|" + (mediaType != null ? mediaType : "") + "|" + (mediaId != null ? mediaId : "");

            // Detect edit: cached content exists, is different from current, and wasn't already marked
            if (existing != null && !existing.isEdited && !existing.isRecalled) {
                String existingFingerprint = (existing.text != null ? existing.text : "") + "|"
                        + (existing.mediaType != null ? existing.mediaType : "") + "|"
                        + (existing.mediaId != null ? existing.mediaId : "");
                if (!existingFingerprint.isEmpty() && !existingFingerprint.equals(contentFingerprint)) {
                    messageCache.markEdited(dialogId, messageId, fullContent);
                    info("Anti-recall: detected edit msgId=" + messageId);
                } else if (existingFingerprint.isEmpty() || existingFingerprint.equals(contentFingerprint)) {
                    cachedMediaPath = tryCacheMedia(cell, dialogId, messageId, owner, mediaAttachment, mediaKind);
                    messageCache.putFresh(dialogId, messageId, fullContent, caption, 0L, mediaType, mediaId, cachedMediaPath);
                    messageCache.putMediaObject(dialogId, messageId, media);
                    prefetchMediaIfNeeded(dialogId, messageId, messageObject, media, cachedMediaPath);
                }
            } else if (existing == null || (!existing.isEdited && !existing.isRecalled)) {
                cachedMediaPath = tryCacheMedia(cell, dialogId, messageId, owner, mediaAttachment, mediaKind);
                messageCache.putFresh(dialogId, messageId, fullContent, caption, 0L, mediaType, mediaId, cachedMediaPath);
                messageCache.putMediaObject(dialogId, messageId, media);
                prefetchMediaIfNeeded(dialogId, messageId, messageObject, media, cachedMediaPath);
            }

            MessageCache.CachedMessage cached = messageCache.get(dialogId, messageId);
            if (cached != null && cached.isEdited) {
                showEditedMark(cell, cached);
            } else if (cached != null && cached.isRecalled) {
                showRecalledMark(cell, cached);
            }
        } catch (Throwable t) {
            error("Anti-recall: cacheAndApply failed", t);
        }
    }

    private void prefetchMediaIfNeeded(long dialogId, long messageId, Object messageLike,
                                       Object media, String cachedMediaPath) {
        if (cachedMediaPath != null || mediaPrefetcher == null || messageLike == null || media == null) {
            return;
        }
        mediaPrefetcher.prefetchFromMessage(dialogId, messageId, messageLike, media);
    }

    private String tryCacheMedia(View cell, long dialogId, long messageId, Object messageOwner, Object mediaObj, String mediaKind) {
        if (mediaObj == null || mediaKind == null) {
            return null;
        }
        try {
            // Check if we already cached this media
            String extension = mediaKind.equals("photo") ? ".jpg" : mediaKind.equals("video") ? ".mp4" : ".bin";
            if (mediaCache.hasMedia(dialogId, messageId, extension)) {
                return mediaCache.getMediaFile(dialogId, messageId, extension).getAbsolutePath();
            }

            // Try to get the file path from Telegram's FileLoader
            ClassLoader classLoader = savedClassLoader != null ? savedClassLoader : cell.getContext().getClassLoader();
            Class<?> fileLoaderClass = classLoader.loadClass("org.telegram.messenger.FileLoader");
            Object fileLoader = Reflect.invokeStatic(fileLoaderClass, "getInstance",
                    new Class<?>[]{int.class}, resolveSelectedTelegramAccount(classLoader));

            java.io.File srcFile = resolveTelegramMediaPath(fileLoaderClass, fileLoader, messageOwner, mediaObj);
            if (srcFile != null && srcFile.exists() && srcFile.length() > 0) {
                try (java.io.FileInputStream fis = new java.io.FileInputStream(srcFile)) {
                    java.io.File cached = mediaCache.saveMedia(dialogId, messageId, extension, fis);
                    if (cached != null) {
                        info("Anti-recall: cached media msgId=" + messageId + " kind=" + mediaKind);
                        return cached.getAbsolutePath();
                    }
                }
            }
        } catch (Throwable t) {
            info("Anti-recall: media cache miss kind=" + mediaKind + " reason=" + t.getClass().getSimpleName());
        }
        return null;
    }

    private java.io.File resolveTelegramMediaPath(Class<?> fileLoaderClass, Object fileLoader, Object messageOwner, Object mediaObj) {
        java.io.File file = invokePathMethod(fileLoaderClass, null, "getPathToMessage", messageOwner);
        if (file != null) return file;
        file = invokePathMethod(fileLoaderClass, fileLoader, "getPathToMessage", messageOwner);
        if (file != null) return file;
        file = invokePathMethod(fileLoaderClass, null, "getPathToAttach", mediaObj);
        if (file != null) return file;
        return invokePathMethod(fileLoaderClass, fileLoader, "getPathToAttach", mediaObj);
    }

    private java.io.File invokePathMethod(Class<?> type, Object target, String name, Object payload) {
        if (payload == null) {
            return null;
        }
        boolean needStatic = target == null;
        Class<?> current = type;
        while (current != null) {
            for (Method method : current.getDeclaredMethods()) {
                if (!method.getName().equals(name) || Modifier.isStatic(method.getModifiers()) != needStatic) {
                    continue;
                }
                Object[] args = buildFilePathArgs(method.getParameterTypes(), payload);
                if (args == null) {
                    continue;
                }
                try {
                    method.setAccessible(true);
                    Object result = method.invoke(target, args);
                    if (result instanceof java.io.File) {
                        return (java.io.File) result;
                    }
                } catch (Throwable ignored) {
                    // Try the next overload.
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }

    static Object[] buildFilePathArgs(Class<?>[] parameterTypes, Object payload) {
        if (parameterTypes.length == 1) {
            return new Object[]{payload};
        }
        if (parameterTypes.length == 2 && (parameterTypes[1] == boolean.class || parameterTypes[1] == Boolean.class)) {
            return new Object[]{payload, false};
        }
        return null;
    }

    private void addColorIndicator(View cell, int color) {
        info("Anti-recall: addColorIndicator called, cell class=" + cell.getClass().getSimpleName());
        if (!(cell instanceof ViewGroup)) {
            info("Anti-recall: cell is NOT a ViewGroup, cannot add indicator");
            return;
        }
        ViewGroup group = (ViewGroup) cell;
        if (group.findViewWithTag("gramsieve_indicator") != null) {
            info("Anti-recall: indicator already exists");
            return;
        }
        Context context = cell.getContext();
        View indicator = new View(context);
        indicator.setTag("gramsieve_indicator");
        indicator.setBackgroundColor(color);
        int height = (int) (3 * context.getResources().getDisplayMetrics().density);
        ViewGroup.LayoutParams lp = new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height);
        group.addView(indicator, lp);
        info("Anti-recall: indicator added, height=" + height);
    }

    private void applyOverlay(View cell, int color) {
        try {
            android.graphics.drawable.ColorDrawable drawable = new android.graphics.drawable.ColorDrawable(color);
            drawable.setBounds(0, 0, cell.getWidth(), cell.getHeight());
            cell.getOverlay().add(drawable);
        } catch (Throwable t) {
            error("Anti-recall: overlay failed", t);
        }
    }

    private void showOriginalContentDialog(View anchor, String originalText, boolean isRecalled) {
        if (originalText == null || originalText.isEmpty()) return;
        Context context = anchor.getContext();
        String title = isRecalled
                ? (isChineseLocale(context) ? "此消息已被撤回" : "Message Recalled")
                : (isChineseLocale(context) ? "原始消息内容" : "Original Message");
        android.app.AlertDialog originalDialog = new android.app.AlertDialog.Builder(context)
                .setTitle(title)
                .setMessage(originalText)
                .setPositiveButton("OK", null)
                .create();
        originalDialog.show();
        styleSystemDialog(originalDialog, context);
    }

    private void showEditDialog(View anchor, String originalText, String editedText) {
        info("Anti-recall: showEditDialog orig=" + (originalText == null ? "NULL" : originalText.length() + "chars") + " edit=" + (editedText == null ? "NULL" : editedText.length() + "chars"));
        Context context = anchor.getContext();
        String title = isChineseLocale(context) ? "消息已编辑" : "Message Edited";
        String orig = sanitizeDisplayText(originalText);
        String edit = sanitizeDisplayText(editedText);
        if (orig == null && edit == null) {
            Toast.makeText(context, isChineseLocale(context) ? "无编辑历史" : "No edit history", Toast.LENGTH_SHORT).show();
            return;
        }
        StringBuilder body = new StringBuilder();
        if (orig != null) {
            body.append(isChineseLocale(context) ? "原始内容：\n" : "Original:\n");
            body.append(orig);
        }
        if (edit != null) {
            if (body.length() > 0) body.append("\n\n");
            body.append(isChineseLocale(context) ? "编辑后：\n" : "Edited:\n");
            body.append(edit);
        }
        if (body.length() == 0) return;
        android.app.AlertDialog editDialog = new android.app.AlertDialog.Builder(context)
                .setTitle(title)
                .setMessage(body.toString())
                .setPositiveButton("OK", null)
                .create();
        editDialog.show();
        styleSystemDialog(editDialog, context);
    }

    private void showEditHistoryDialog(View anchor, List<MessageCache.CachedMessage> history,
                                       MessageCache.CachedMessage fallback) {
        if (history == null || history.isEmpty()) {
            showEditDialog(anchor, firstNonEmpty(fallback.text, fallback.caption), fallback.editedText);
            return;
        }
        if (history.size() == 1) {
            MessageCache.CachedMessage only = history.get(0);
            showEditDialog(anchor, firstNonEmpty(only.text, only.caption), only.editedText);
            return;
        }
        Context context = anchor.getContext();
        StringBuilder body = new StringBuilder();
        int version = 1;
        for (int i = history.size() - 1; i >= 0; i--) {
            MessageCache.CachedMessage item = history.get(i);
            String before = sanitizeDisplayText(firstNonEmpty(item.text, item.caption));
            String after = sanitizeDisplayText(item.editedText);
            if (before == null && after == null) {
                continue;
            }
            if (body.length() > 0) {
                body.append("\n\n");
            }
            body.append(isChineseLocale(context) ? "版本 " : "Version ")
                    .append(version++)
                    .append(":\n");
            if (before != null) {
                body.append(isChineseLocale(context) ? "编辑前：\n" : "Before:\n")
                        .append(before);
            }
            if (after != null) {
                if (before != null) {
                    body.append("\n\n");
                }
                body.append(isChineseLocale(context) ? "编辑后：\n" : "After:\n")
                        .append(after);
            }
        }
        if (body.length() == 0) {
            showEditDialog(anchor, firstNonEmpty(fallback.text, fallback.caption), fallback.editedText);
            return;
        }
        android.app.AlertDialog historyDialog = new android.app.AlertDialog.Builder(context)
                .setTitle(isChineseLocale(context) ? "编辑历史" : "Edit History")
                .setMessage(body.toString())
                .setPositiveButton("OK", null)
                .create();
        historyDialog.show();
        styleSystemDialog(historyDialog, context);
    }

    private static String sanitizeDisplayText(String text) {
        if (text == null || text.isEmpty() || "null".equals(text)) return null;
        return text;
    }

    private static String firstNonEmpty(String first, String second) {
        if (first != null && !first.isEmpty()) {
            return first;
        }
        if (second != null && !second.isEmpty()) {
            return second;
        }
        return null;
    }

    private void replaceTextInCell(View cell, String originalText) {
        if (originalText == null || originalText.isEmpty()) return;
        try {
            if (cell instanceof ViewGroup) {
                TextView tv = findLargestTextView((ViewGroup) cell);
                if (tv != null) {
                    String current = tv.getText().toString();
                    if (!originalText.equals(current)) {
                        tv.setText(originalText);
                        info("Anti-recall: replaced text in cell");
                    }
                }
            }
        } catch (Throwable throwable) {
            error("Anti-recall: replaceTextInCell failed", throwable);
        }
    }

    private TextView findLargestTextView(ViewGroup group) {
        TextView largest = null;
        int maxLen = 0;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof TextView && child.getVisibility() == View.VISIBLE) {
                String text = ((TextView) child).getText().toString();
                if (text.length() > maxLen) {
                    maxLen = text.length();
                    largest = (TextView) child;
                }
            } else if (child instanceof ViewGroup) {
                TextView found = findLargestTextView((ViewGroup) child);
                if (found != null) {
                    String text = found.getText().toString();
                    if (text.length() > maxLen) {
                        maxLen = text.length();
                        largest = found;
                    }
                }
            }
        }
        return largest;
    }

    private void applyEditedMessageVisuals(View cell, Object messageObject, MessageCache.CachedMessage cachedMessage) {
        showEditedMark(cell, cachedMessage);
        applyRestoredContent(cell, messageObject, cachedMessage);
    }

    private void applyRestoredContent(View cell, Object messageObject, MessageCache.CachedMessage cachedMessage) {
        interceptAndRestoreContent(messageObject, cachedMessage);
        replaceTextInCell(cell, firstNonEmpty(cachedMessage.text, cachedMessage.caption));
        replaceCachedMediaInCell(cell, cachedMessage);
    }

    private void replaceCachedMediaInCell(View cell, MessageCache.CachedMessage cachedMessage) {
        if (!(cell instanceof ViewGroup) || cachedMessage.cachedMediaPath == null) {
            return;
        }
        try {
            java.io.File file = new java.io.File(cachedMessage.cachedMediaPath);
            if (!file.exists() || file.length() <= 0) {
                return;
            }
            android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeFile(file.getAbsolutePath());
            if (bitmap == null) {
                return;
            }
            ImageView imageView = findLargestImageView((ViewGroup) cell);
            if (imageView != null && imageView.getWidth() > 0 && imageView.getHeight() > 0) {
                imageView.setImageBitmap(bitmap);
                info("Anti-recall: restored cached media via ImageView for msg " + cachedMessage.messageId);
            }
            if (applyCachedMediaOverlay(cell, bitmap)) {
                info("Anti-recall: restored cached media via overlay for msg " + cachedMessage.messageId);
            }
        } catch (Throwable throwable) {
            error("Anti-recall: replaceCachedMediaInCell failed", throwable);
        }
    }

    private boolean applyCachedMediaOverlay(View cell, android.graphics.Bitmap bitmap) {
        if (cell.getWidth() <= 0 || cell.getHeight() <= 0) {
            return false;
        }
        Object old = cell.getTag(R.id.gramsieve_view_state);
        if (old instanceof android.graphics.drawable.Drawable) {
            cell.getOverlay().remove((android.graphics.drawable.Drawable) old);
        }
        android.graphics.drawable.Drawable drawable = createCenterCropDrawable(bitmap);
        int inset = dp(cell.getContext(), 6f);
        drawable.setBounds(inset, inset, Math.max(inset, cell.getWidth() - inset), Math.max(inset, cell.getHeight() - inset));
        cell.getOverlay().add(drawable);
        cell.setTag(R.id.gramsieve_view_state, drawable);
        cell.invalidate();
        return true;
    }

    private android.graphics.drawable.Drawable createCenterCropDrawable(android.graphics.Bitmap bitmap) {
        return new android.graphics.drawable.Drawable() {
            private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG | android.graphics.Paint.FILTER_BITMAP_FLAG);

            @Override
            public void draw(android.graphics.Canvas canvas) {
                android.graphics.Rect bounds = getBounds();
                if (bounds.isEmpty()) {
                    return;
                }
                float scale = Math.max(
                        bounds.width() / (float) bitmap.getWidth(),
                        bounds.height() / (float) bitmap.getHeight()
                );
                float scaledWidth = bitmap.getWidth() * scale;
                float scaledHeight = bitmap.getHeight() * scale;
                float left = bounds.left + (bounds.width() - scaledWidth) / 2f;
                float top = bounds.top + (bounds.height() - scaledHeight) / 2f;
                android.graphics.RectF dst = new android.graphics.RectF(left, top, left + scaledWidth, top + scaledHeight);
                canvas.save();
                canvas.clipRect(bounds);
                canvas.drawBitmap(bitmap, null, dst, paint);
                canvas.restore();
            }

            @Override
            public void setAlpha(int alpha) {
                paint.setAlpha(alpha);
                invalidateSelf();
            }

            @Override
            public void setColorFilter(android.graphics.ColorFilter colorFilter) {
                paint.setColorFilter(colorFilter);
                invalidateSelf();
            }

            @Override
            public int getOpacity() {
                return android.graphics.PixelFormat.OPAQUE;
            }
        };
    }

    private ImageView findLargestImageView(ViewGroup group) {
        ImageView largest = null;
        int maxArea = 0;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof ImageView && child.getVisibility() == View.VISIBLE) {
                int area = child.getWidth() * child.getHeight();
                if (area > maxArea) {
                    maxArea = area;
                    largest = (ImageView) child;
                }
            } else if (child instanceof ViewGroup) {
                ImageView found = findLargestImageView((ViewGroup) child);
                if (found != null) {
                    int area = found.getWidth() * found.getHeight();
                    if (area > maxArea) {
                        maxArea = area;
                        largest = found;
                    }
                }
            }
        }
        return largest;
    }

    private void applyAntiRecallMark(View cell, Object messageObject) {
        if (messageObject == null || messageCache == null) {
            return;
        }
        long dialogId = Reflect.asLong(Reflect.invokeIfExists(messageObject, "getDialogId", new Class<?>[0]), 0L);
        long messageId = Reflect.asLong(Reflect.invokeIfExists(messageObject, "getId", new Class<?>[0]), 0L);
        if (dialogId == 0L || messageId == 0L) {
            return;
        }
        MessageCache.CachedMessage cachedMessage = messageCache.get(dialogId, messageId);
        if (cachedMessage == null) {
            return;
        }
        if (cachedMessage.isRecalled) {
            showRecalledMark(cell, cachedMessage);
            replaceCachedMediaInCell(cell, cachedMessage);
        } else if (cachedMessage.isEdited) {
            showEditedMark(cell, cachedMessage);
        }
    }

    private void interceptAndRestoreContent(Object messageObject, MessageCache.CachedMessage cachedMessage) {
        try {
            // Get the messageOwner object
            Object messageOwner = Reflect.field(messageObject, "messageOwner");
            if (messageOwner == null) {
                return;
            }
            
            // Restore original text content
            String currentText = Reflect.asString(Reflect.field(messageOwner, "message"));
            if (cachedMessage.text != null && !cachedMessage.text.equals(currentText)) {
                Reflect.setField(messageOwner, "message", cachedMessage.text);
                info("Anti-recall: restored original text for msg " + cachedMessage.messageId);
            }
            
            // Restore original caption if it exists
            Object media = Reflect.field(messageOwner, "media");
            if (media != null) {
                String currentCaption = Reflect.asString(Reflect.field(media, "caption"));
                if (cachedMessage.caption != null && !cachedMessage.caption.equals(currentCaption)) {
                    Reflect.setField(media, "caption", cachedMessage.caption);
                    info("Anti-recall: restored original caption for msg " + cachedMessage.messageId);
                }
            }
        } catch (Throwable throwable) {
            error("Anti-recall: interceptAndRestoreContent failed", throwable);
        }
    }

    private void clearAntiRecallCellVisualState(View cell) {
        Object mark = cell.getTag(R.id.gramsieve_menu_item_id);
        if (!(mark instanceof String)) {
            return;
        }
        String value = (String) mark;
        if (!value.startsWith("recalled_") && !value.startsWith("edited_")) {
            return;
        }
        cell.setTag(R.id.gramsieve_menu_item_id, null);
        if (value.startsWith("recalled_")) {
            cell.setBackground(null);
        }
    }

    private void showRecalledMark(View cell, MessageCache.CachedMessage cachedMessage) {
        Context context = cell.getContext();
        String originalContent = cachedMessage.text != null && !cachedMessage.text.isEmpty() 
            ? cachedMessage.text 
            : (cachedMessage.caption != null && !cachedMessage.caption.isEmpty() ? cachedMessage.caption : "");
        
        final String markText;
        if (isChineseLocale(context)) {
            markText = "[此消息已被撤回]" + (originalContent.isEmpty() ? "" : "\n原内容: " + originalContent);
        } else {
            markText = "[This message was recalled]" + (originalContent.isEmpty() ? "" : "\nOriginal: " + originalContent);
        }
        
        cell.setTag(R.id.gramsieve_menu_item_id, "recalled_" + cachedMessage.messageId);
        cell.post(() -> Toast.makeText(context, markText, Toast.LENGTH_LONG).show());
    }

    private void showEditedMark(View cell, MessageCache.CachedMessage cachedMessage) {
        cell.setTag(R.id.gramsieve_menu_item_id, "edited_" + cachedMessage.messageId);
    }

    private Object handleCellLifecycle(XposedInterface.Chain chain) throws Throwable {
        emitHookEntry("lifecycle", chain.getThisObject(), null);
        Object result = chain.proceed();
        try {
            Object cell = chain.getThisObject();
            if (cell instanceof View) {
                View cellView = (View) cell;
                trackTopmostMessage(cellView);
                Object messageObject = resolveMessageObject(cell);
                if (messageObject != null) {
                    applyDecision(cellView, cellView, messageObject);
                } else {
                    applyDecisionToBoundViews(cell);
                }
            }
        } catch (Throwable throwable) {
            error("Cell lifecycle filtering failed", throwable);
        }
        return result;
    }

    private Object handleCellMeasure(XposedInterface.Chain chain) throws Throwable {
        Object result = chain.proceed();
        try {
            Object cell = chain.getThisObject();
            if (cell instanceof View) {
                View messageView = (View) cell;
                DecisionContext context = evaluateDecisionContext(messageView, resolveMessageObject(cell));
                UiMutation.overrideMeasuredHeight(messageView, context.decision);
            }
        } catch (Throwable throwable) {
            error("Cell measure filtering failed", throwable);
        }
        return result;
    }

    private Object handleChatRowBinding(XposedInterface.Chain chain) throws Throwable {
        emitHookEntry("chatRow", chain.getThisObject(), chain.getArg(0));
        Object result = chain.proceed();
        try {
            Object holder = chain.getArg(0);
            Object itemView = Reflect.field(holder, "itemView");
            applyDecisionToBoundViews(itemView);
        } catch (Throwable throwable) {
            error("Chat row filtering failed", throwable);
        }
        return result;
    }

    private Object handleRecyclerViewBinding(XposedInterface.Chain chain) throws Throwable {
        emitHookEntry("recycler", chain.getThisObject(), chain.getArg(0));
        Object result = chain.proceed();
        try {
            Object adapter = chain.getThisObject();
            Object holder = chain.getArg(0);
            Object itemView = Reflect.field(holder, "itemView");
            if (applyLocalDialogHide(adapter, itemView)) {
                return result;
            }
            applyDecisionToBoundViews(itemView);
        } catch (Throwable throwable) {
            error("RecyclerView binding filter failed", throwable);
        }
        return result;
    }

    private Object handleRecyclerViewAttachment(XposedInterface.Chain chain) throws Throwable {
        emitHookEntry("attach", chain.getThisObject(), chain.getArg(0));
        Object result = chain.proceed();
        try {
            Object adapter = chain.getThisObject();
            Object holder = chain.getArg(0);
            Object itemView = Reflect.field(holder, "itemView");
            if (applyLocalDialogHide(adapter, itemView)) {
                return result;
            }
            applyDecisionToBoundViews(itemView);
        } catch (Throwable throwable) {
            error("RecyclerView attachment filter failed", throwable);
        }
        return result;
    }

    private boolean applyLocalDialogHide(Object adapter, Object itemView) {
        if (!(itemView instanceof View)) {
            return false;
        }
        View row = (View) itemView;
        if (!isDialogListRow(adapter, row)) {
            return false;
        }
        return applyLocalDialogHideToRow(row, resolveDialogDeletionAccount(adapter));
    }

    private boolean applyLocalDialogHideToRow(View row, int account) {
        long dialogId = resolveDialogIdFromView(row);
        if (dialogId == 0L) {
            UiMutation.apply(row, FilterDecision.allow(), "");
            return false;
        }
        if (!isLocallyHiddenDialog(dialogId, account)) {
            UiMutation.apply(row, FilterDecision.allow(), "dialog:" + dialogId);
            return false;
        }
        UiMutation.apply(row,
                FilterDecision.matched(FilterConfig.Action.HIDE, "local-dialog-delete", "local dialog delete"),
                "dialog:" + dialogId);
        if (shouldLogLocalDialogHide(dialogId)) {
            info("DialogDeleteTrace: hiding dialog row dialogId=" + dialogId
                    + " account=" + account
                    + " row=" + row.getClass().getName());
        }
        return true;
    }

    private boolean isDialogsAdapter(Object adapter) {
        if (adapter == null) {
            return false;
        }
        String className = adapter.getClass().getName();
        return "org.telegram.ui.Adapters.DialogsAdapter".equals(className)
                || className.startsWith("org.telegram.ui.DialogsActivity$");
    }

    private boolean isDialogListRow(Object adapter, View row) {
        return isDialogsAdapter(adapter)
                || containsDialogListCell(row);
    }

    private boolean containsDialogListCell(View view) {
        if (isDialogListCellClass(view.getClass().getName())) {
            return true;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (containsDialogListCell(group.getChildAt(i))) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isDialogListCellClass(String className) {
        return "org.telegram.ui.Cells.DialogCell".equals(className)
                || "org.telegram.ui.Cells.ProfileSearchCell".equals(className);
    }

    private long resolveDialogIdFromView(View view) {
        long dialogId = Reflect.asLong(Reflect.invokeIfExists(view, "getDialogId", new Class<?>[0]), 0L);
        if (dialogId != 0L) {
            return dialogId;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                dialogId = resolveDialogIdFromView(group.getChildAt(i));
                if (dialogId != 0L) {
                    return dialogId;
                }
            }
        }
        return 0L;
    }

    private boolean shouldLogLocalDialogHide(long dialogId) {
        long now = System.currentTimeMillis();
        String key = "local-dialog-hide:" + dialogId;
        synchronized (recentDiagnosticKeys) {
            Long previous = recentDiagnosticKeys.get(key);
            if (previous != null && now - previous < 10_000L) {
                return false;
            }
            recentDiagnosticKeys.put(key, now);
            return true;
        }
    }

    private void applyDecisionToBoundViews(Object rootCandidate) {
        if (!(rootCandidate instanceof View)) {
            return;
        }
        View rowRoot = (View) rootCandidate;
        final DecisionContext[] matchedContext = new DecisionContext[1];
        final View[] matchedView = new View[1];
        boolean found = BoundMessageViewWalker.visit(
                rowRoot,
                (messageView, messageObject) -> {
                    DecisionContext context = evaluateDecisionContext(messageView, messageObject);
                    if (context.decision.matched && matchedContext[0] == null) {
                        matchedContext[0] = context;
                        matchedView[0] = messageView;
                    }
                }
        );
        if (matchedContext[0] != null) {
            applyDecisionContext(matchedView[0], rowRoot, matchedContext[0]);
            return;
        }
        if (!found) {
            UiMutation.apply(rowRoot, FilterDecision.allow(), "");
            return;
        }
        UiMutation.apply(rowRoot, FilterDecision.allow(), "");
    }

    private void applyDecision(View messageView, View mutationTarget, Object messageObject) {
        if (messageView == null) {
            return;
        }
        if (mutationTarget == null) {
            mutationTarget = messageView;
        }
        DecisionContext context = evaluateDecisionContext(messageView, messageObject);
        applyDecisionContext(messageView, mutationTarget, context);
    }

    private void applyDecisionContext(View messageView, View mutationTarget, DecisionContext context) {
        if (mutationTarget == null) {
            return;
        }
        UiMutation.apply(mutationTarget, context.decision, context.stableKey);
        if (messageView != null && mutationTarget != messageView && context.decision.matched) {
            UiMutation.apply(messageView, context.decision, context.stableKey);
        }
        View recyclerRow = findRecyclerDirectChild(messageView);
        if (recyclerRow != null && recyclerRow != mutationTarget && context.decision.matched) {
            UiMutation.apply(recyclerRow, context.decision, context.stableKey);
        }
        if (context.snapshot == null || context.config == null) {
            return;
        }
        emitDecisionProbe(context.config, context.snapshot, context.decision);
        emitBindingProbe(context.config, mutationTarget, context.snapshot, context.decision);
        persistDiagnostic(mutationTarget.getContext().getApplicationContext(), context);
        if (context.config.debugLogging && (context.decision.matched || context.decision.excluded)) {
            info("Decision=" + context.decision.reason + " dialog=" + context.snapshot.dialogId + " sender=" + context.snapshot.senderId + " msg=" + context.snapshot.messageId);
        }
    }

    private DecisionContext evaluateDecisionContext(View messageView, Object messageObject) {
        if (messageView == null) {
            return DecisionContext.allow();
        }
        MessageSnapshot snapshot = TelegramMessageNormalizer.normalize(messageView, messageObject);
        if (snapshot == null) {
            return DecisionContext.allow();
        }
        FilterConfig config = configProvider.getConfig(messageView.getContext().getApplicationContext());
        FilterDecision decision = decisionCache.get(config, snapshot, () -> filterEngine.evaluate(config, snapshot));
        markFilteredMessageRead(messageObject, snapshot, decision);
        return new DecisionContext(config, snapshot, decision);
    }

    private void markFilteredMessageRead(Object messageObject, MessageSnapshot snapshot, FilterDecision decision) {
        if (!shouldMarkFilteredMessageRead(messageObject, snapshot, decision)) {
            return;
        }
        int messageId = safeMessageId(snapshot);
        String key = snapshot.dialogId + ":" + messageId + ":" + decision.ruleId;
        if (!rememberReadMarkKey(key)) {
            return;
        }
        try {
            Object controller = resolveMessagesController(messageObject);
            if (controller == null) {
                emitReadMarkProbe("controller-missing", snapshot, decision, null);
                return;
            }
            Reflect.invokeIfExists(messageObject, "setIsRead", new Class<?>[0]);
            long topicId = resolveTopicId(messageObject);
            if (invokeMarkDialogAsRead(controller, snapshot.dialogId, messageId, topicId)) {
                decrementDialogUnreadCount(controller, snapshot.dialogId);
                emitReadMarkProbe("marked", snapshot, decision, null);
            } else {
                emitReadMarkProbe("method-missing", snapshot, decision, null);
            }
        } catch (RuntimeException exception) {
            emitReadMarkProbe("failed", snapshot, decision, exception);
        }
    }

    private void markLoadedFilteredMessagesAsRead(Object chatActivity) {
        try {
            long dialogId = Reflect.asLong(Reflect.invokeIfExists(chatActivity, "getDialogId", new Class<?>[0]), 0L);
            if (dialogId == 0L) {
                return;
            }
            Object messages = Reflect.field(chatActivity, "messages");
            if (!(messages instanceof java.util.List)) {
                return;
            }
            java.util.List<?> msgList = (java.util.List<?>) messages;
            if (msgList.isEmpty()) {
                return;
            }
            FilterConfig config = configProvider.getConfig(resolveContextFromActivity(chatActivity));
            if (config == null || !config.enabled) {
                return;
            }
            int filteredCount = 0;
            int latestMessageId = 0;
            Object anyMessageObject = null;
            for (Object msg : msgList) {
                if (msg == null) continue;
                if (anyMessageObject == null) {
                    anyMessageObject = msg;
                }
                int id = resolveMessageId(msg);
                if (id > latestMessageId) {
                    latestMessageId = id;
                }
                MessageSnapshot snapshot = TelegramMessageNormalizer.normalize(null, msg);
                if (snapshot == null) continue;
                FilterDecision decision = decisionCache.get(config, snapshot, () -> filterEngine.evaluate(config, snapshot));
                if (decision.matched && !decision.excluded) {
                    filteredCount++;
                }
            }
            if (filteredCount > 0 && anyMessageObject != null && latestMessageId > 0) {
                Object controller = resolveMessagesController(anyMessageObject);
                if (controller != null) {
                    long topicId = 0;
                    invokeMarkDialogAsRead(controller, dialogId, latestMessageId, topicId);
                    decrementDialogUnreadByFilteredCount(controller, dialogId, filteredCount);
                    info("Pause: markDialogAsRead dialog=" + dialogId + " maxId=" + latestMessageId + " filtered=" + filteredCount);
                }
            }
        } catch (Throwable throwable) {
            error("Pause filtered message scan failed", throwable);
        }
    }

    private void decrementDialogUnreadByFilteredCount(Object controller, long dialogId, int filteredCount) {
        try {
            Object dialog = resolveDialog(controller, dialogId);
            if (dialog == null) {
                return;
            }
            int currentUnread = Reflect.asInt(Reflect.field(dialog, "unread_count"), 0);
            if (currentUnread <= 0) {
                return;
            }
            int decrement = Math.min(currentUnread, filteredCount);
            int newCount = currentUnread - decrement;
            java.lang.reflect.Field unreadField = findDialogUnreadCountField(dialog.getClass());
            if (unreadField == null) {
                return;
            }
            unreadField.setAccessible(true);
            Class<?> type = unreadField.getType();
            if (type == int.class) {
                unreadField.setInt(dialog, newCount);
            } else if (type == Integer.class) {
                unreadField.set(dialog, newCount);
            } else if (type == long.class) {
                unreadField.setLong(dialog, newCount);
            } else if (type == Long.class) {
                unreadField.set(dialog, (long) newCount);
            }
            if (decrement > 0) {
                info("ReadMark-decr: unread_count " + currentUnread + " -> " + newCount + " (filtered=" + filteredCount + ") dialog=" + dialogId);
            }
        } catch (Throwable ignored) {
        }
    }

    private boolean shouldMarkFilteredMessageRead(Object messageObject, MessageSnapshot snapshot, FilterDecision decision) {
        if (messageObject == null || snapshot == null || decision == null || !decision.matched || decision.excluded) {
            return false;
        }
        if (decision.action == FilterConfig.Action.DEBUG_MARK || safeMessageId(snapshot) <= 0 || snapshot.dialogId == 0L) {
            return false;
        }
        Object out = Reflect.invokeIfExists(messageObject, "isOut", new Class<?>[0]);
        if (Boolean.TRUE.equals(out)) {
            return false;
        }
        Object outOwner = Reflect.invokeIfExists(messageObject, "isOutOwner", new Class<?>[0]);
        if (Boolean.TRUE.equals(outOwner)) {
            return false;
        }
        Object unread = Reflect.invokeIfExists(messageObject, "isUnread", new Class<?>[0]);
        if (Boolean.FALSE.equals(unread)) {
            info("ReadMark-skip: isUnread=false dialog=" + snapshot.dialogId + " msg=" + snapshot.messageId);
            return false;
        }
        Object messageOwner = Reflect.field(messageObject, "messageOwner");
        Object ownerUnread = Reflect.field(messageOwner, "unread");
        if (Boolean.FALSE.equals(ownerUnread)) {
            info("ReadMark-skip: ownerUnread=false dialog=" + snapshot.dialogId + " msg=" + snapshot.messageId);
            return false;
        }
        return true;
    }

    private boolean rememberReadMarkKey(String key) {
        synchronized (recentReadMarkKeys) {
            if (recentReadMarkKeys.containsKey(key)) {
                return false;
            }
            recentReadMarkKeys.put(key, System.currentTimeMillis());
            return true;
        }
    }

    private Object resolveMessagesController(Object messageObject) {
        try {
            int account = Reflect.asInt(Reflect.field(messageObject, "currentAccount"), 0);
            ClassLoader classLoader = messageObject.getClass().getClassLoader();
            Class<?> controllerClass = classLoader.loadClass("org.telegram.messenger.MessagesController");
            return Reflect.invokeStatic(controllerClass, "getInstance", new Class<?>[]{int.class}, account);
        } catch (ClassNotFoundException exception) {
            return null;
        }
    }

    private long resolveTopicId(Object messageObject) {
        Object topicId = Reflect.invokeIfExists(messageObject, "getTopicId", new Class<?>[0]);
        return Reflect.asLong(topicId, 0L);
    }

    private boolean invokeMarkDialogAsRead(Object controller, long dialogId, int messageId, long topicId) {
        try {
            Method method = Reflect.method(
                    controller.getClass(),
                    "markDialogAsRead",
                    long.class,
                    int.class,
                    int.class,
                    int.class,
                    boolean.class,
                    long.class,
                    int.class,
                    boolean.class,
                    int.class
            );
            Reflect.invoke(method, controller, dialogId, messageId, messageId, 0, true, topicId, 0, true, 0);
            return true;
        } catch (NoSuchMethodException ignored) {
            return invokeCompatibleMarkDialogAsRead(controller, dialogId, messageId, topicId);
        }
    }

    private boolean invokeCompatibleMarkDialogAsRead(Object controller, long dialogId, int messageId, long topicId) {
        Class<?> current = controller.getClass();
        while (current != null) {
            for (Method method : current.getDeclaredMethods()) {
                if (!"markDialogAsRead".equals(method.getName())) {
                    continue;
                }
                Object[] args = buildMarkDialogAsReadArgs(method.getParameterTypes(), dialogId, messageId, topicId);
                if (args == null) {
                    continue;
                }
                try {
                    method.setAccessible(true);
                    Reflect.invoke(method, controller, args);
                    return true;
                } catch (RuntimeException ignored) {
                    // Try the next overload if Telegram changed the parameter semantics.
                }
            }
            current = current.getSuperclass();
        }
        return false;
    }

    private void decrementDialogUnreadCount(Object controller, long dialogId) {
        try {
            Object dialog = resolveDialog(controller, dialogId);
            if (dialog == null) {
                info("ReadMark-decr: dialog not found dialog=" + dialogId);
                return;
            }
            int currentUnread = Reflect.asInt(Reflect.field(dialog, "unread_count"), 0);
            info("ReadMark-decr: dialog found unread_count=" + currentUnread + " dialog=" + dialogId);
            if (currentUnread <= 0) {
                return;
            }
            java.lang.reflect.Field unreadField = findDialogUnreadCountField(dialog.getClass());
            if (unreadField != null) {
                unreadField.setAccessible(true);
                Class<?> type = unreadField.getType();
                int newCount = Math.max(0, currentUnread - 1);
                if (type == int.class) {
                    unreadField.setInt(dialog, newCount);
                } else if (type == Integer.class) {
                    unreadField.set(dialog, newCount);
                } else if (type == long.class) {
                    unreadField.setLong(dialog, newCount);
                } else if (type == Long.class) {
                    unreadField.set(dialog, (long) newCount);
                }
                info("ReadMark-decr: updated unread_count " + currentUnread + " -> " + newCount + " dialog=" + dialogId);
            } else {
                info("ReadMark-decr: unread_count field not found class=" + dialog.getClass().getName());
            }
        } catch (Throwable throwable) {
            info("ReadMark-decr: exception " + throwable.getMessage());
        }
    }

    private java.lang.reflect.Field findDialogUnreadCountField(Class<?> clazz) {
        String[] names = {"unread_count", "unreadCount"};
        for (String name : names) {
            Class<?> current = clazz;
            while (current != null) {
                try {
                    return current.getDeclaredField(name);
                } catch (NoSuchFieldException ignored) {
                }
                current = current.getSuperclass();
            }
        }
        return null;
    }

    private Object resolveDialog(Object controller, long dialogId) {
        Object dialog = Reflect.invokeIfExists(controller, "getDialog", new Class<?>[]{long.class}, dialogId);
        if (dialog != null) {
            return dialog;
        }
        Object dialogsStorage = Reflect.invokeIfExists(controller, "getDialogsStorage", new Class<?>[0]);
        if (dialogsStorage != null) {
            dialog = Reflect.invokeIfExists(dialogsStorage, "getDialog", new Class<?>[]{long.class}, dialogId);
            if (dialog != null) {
                return dialog;
            }
        }
        Object dialogsDict = Reflect.field(controller, "dialogs_dict");
        if (dialogsDict != null) {
            try {
                java.lang.reflect.Method getMethod = dialogsDict.getClass().getMethod("get", long.class);
                dialog = getMethod.invoke(dialogsDict, dialogId);
            } catch (Throwable ignored) {
            }
        }
        if (dialog == null) {
            info("ReadMark-resolve: all lookup methods failed for dialog=" + dialogId);
        }
        return dialog;
    }

    private Object[] buildMarkDialogAsReadArgs(Class<?>[] parameterTypes, long dialogId, int messageId, long topicId) {
        Object[] args = new Object[parameterTypes.length];
        int longCount = 0;
        int intCount = 0;
        int booleanCount = 0;
        for (int i = 0; i < parameterTypes.length; i++) {
            Class<?> parameterType = parameterTypes[i];
            if (parameterType == long.class || parameterType == Long.class) {
                longCount++;
                args[i] = longCount == 1 ? dialogId : (longCount == 2 ? topicId : 0L);
            } else if (parameterType == int.class || parameterType == Integer.class) {
                intCount++;
                args[i] = intCount <= 2 ? messageId : 0;
            } else if (parameterType == boolean.class || parameterType == Boolean.class) {
                booleanCount++;
                args[i] = booleanCount <= 2;
            } else {
                return null;
            }
        }
        return longCount >= 1 && intCount >= 1 ? args : null;
    }

    private int safeMessageId(MessageSnapshot snapshot) {
        if (snapshot == null || snapshot.messageId <= 0L || snapshot.messageId > Integer.MAX_VALUE) {
            return 0;
        }
        return (int) snapshot.messageId;
    }

    private void emitReadMarkProbe(String state, MessageSnapshot snapshot, FilterDecision decision, RuntimeException exception) {
        int remaining = readMarkProbeBudget.getAndDecrement();
        if (remaining <= 0) {
            return;
        }
        String message = "ReadMark state=" + state
                + " dialog=" + (snapshot == null ? 0L : snapshot.dialogId)
                + " msg=" + (snapshot == null ? 0L : snapshot.messageId)
                + " ruleId=" + (decision == null ? "" : decision.ruleId);
        if (exception == null) {
            info(message);
        } else {
            ModuleLogger.warn(ModuleLogger.CAT_HOOK, TAG, message + " error=" + exception.getMessage());
        }
    }

    private Object resolveMessageObject(Object cell) {
        Object messageObject = Reflect.invokeIfExists(cell, "getMessageObject", new Class<?>[0]);
        if (messageObject != null) {
            return messageObject;
        }
        Object currentMessageObject = Reflect.field(cell, "currentMessageObject");
        if (currentMessageObject != null) {
            return currentMessageObject;
        }
        Object fieldMessageObject = Reflect.field(cell, "messageObject");
        if (fieldMessageObject != null) {
            return fieldMessageObject;
        }
        return null;
    }

    private void emitHookEntry(String source, Object target, Object payload) {
        int remaining = hookEntryBudget.getAndDecrement();
        if (remaining <= 0) {
            return;
        }
        info(
                "HookEntry source=" + source
                        + " target=" + classNameOf(target)
                        + " payload=" + classNameOf(payload)
        );
    }

    private String classNameOf(Object value) {
        return value == null ? "null" : value.getClass().getName();
    }

    private void emitDecisionProbe(FilterConfig config, MessageSnapshot snapshot, FilterDecision decision) {
        int remaining = decisionProbeBudget.getAndDecrement();
        if (remaining <= 0) {
            return;
        }
        info(
                "DecisionProbe action=" + config.action
                        + " debug=" + config.debugLogging
                        + " globalRules=" + config.globalRules.size()
                        + " chatRules=" + config.chatRules.size()
                        + " updatedAt=" + config.updatedAtEpochMs
                        + " matched=" + decision.matched
                        + " excluded=" + decision.excluded
                        + " reason=" + preview(decision.reason)
                        + " chat=" + preview(snapshot.chatName)
                        + " sender=" + preview(snapshot.senderName)
                        + " dialog=" + snapshot.dialogId
                        + " text=" + preview(snapshot.text)
        );
    }

    private void emitBindingProbe(FilterConfig config, Object cell, MessageSnapshot snapshot, FilterDecision decision) {
        if (!config.debugLogging) {
            return;
        }
        int remaining = bindingProbeBudget.getAndDecrement();
        if (remaining <= 0) {
            return;
        }
        info(
                "BindProbe cell=" + cell.getClass().getSimpleName()
                        + " chat=" + preview(snapshot.chatName)
                        + " sender=" + preview(snapshot.senderName)
                        + " dialog=" + snapshot.dialogId
                        + " msg=" + snapshot.messageId
                        + " text=" + preview(snapshot.text)
                        + " caption=" + preview(snapshot.caption)
                        + " buttons=" + preview(snapshot.buttonText)
                        + " decision=" + decision.reason
        );
    }

    private void persistDiagnostic(Context context, DecisionContext decisionContext) {
        if (context == null || decisionContext == null || decisionContext.config == null || decisionContext.snapshot == null) {
            return;
        }
        if (!decisionContext.config.debugLogging) {
            return;
        }
        if (persistentDiagnosticsUnavailable) {
            return;
        }
        if (!shouldPersistDiagnostic(decisionContext)) {
            return;
        }
        DiagnosticLogStore.DiagnosticEntry entry = new DiagnosticLogStore.DiagnosticEntry();
        entry.timestampEpochMs = System.currentTimeMillis();
        entry.category = "decision";
        entry.matched = decisionContext.decision.matched;
        entry.excluded = decisionContext.decision.excluded;
        entry.action = decisionContext.decision.matched ? decisionContext.decision.action.name() : "";
        entry.ruleId = decisionContext.decision.ruleId;
        entry.reason = decisionContext.decision.reason;
        entry.likelyGambling = FilterEngine.isLikelyGamblingPromotion(decisionContext.snapshot);
        entry.globalRuleCount = decisionContext.config.globalRules.size();
        entry.chatRuleSetCount = decisionContext.config.chatRules.size();
        entry.dialogId = decisionContext.snapshot.dialogId;
        entry.senderId = decisionContext.snapshot.senderId;
        entry.messageId = decisionContext.snapshot.messageId;
        entry.stableKey = decisionContext.stableKey;
        entry.chatName = decisionContext.snapshot.chatName;
        entry.senderName = decisionContext.snapshot.senderName;
        entry.text = decisionContext.snapshot.text;
        entry.caption = decisionContext.snapshot.caption;
        entry.buttonText = decisionContext.snapshot.buttonText;
        entry.hasInlineButtons = decisionContext.snapshot.hasInlineButtons;
        Bundle extras = new Bundle();
        extras.putString(ConfigContentProvider.KEY_DIAGNOSTIC_ENTRY_JSON, DiagnosticLogStore.entryToJson(entry));
        try {
            context.getContentResolver().call(
                    ConfigContentProvider.CONTENT_URI,
                    ConfigContentProvider.METHOD_APPEND_DIAGNOSTIC,
                    null,
                    extras
            );
        } catch (RuntimeException exception) {
            persistentDiagnosticsUnavailable = true;
            ModuleLogger.warn(ModuleLogger.CAT_HOOK, TAG, "Persistent diagnostic append disabled: " + exception.getMessage());
        }
    }

    private boolean shouldPersistDiagnostic(DecisionContext decisionContext) {
        MessageSnapshot snapshot = decisionContext.snapshot;
        FilterDecision decision = decisionContext.decision;
        boolean interesting = decision.matched
                || decision.excluded
                || FilterEngine.isLikelyGamblingPromotion(snapshot)
                || !snapshot.caption.isBlank()
                || !snapshot.buttonText.isBlank();
        if (!interesting) {
            return false;
        }
        String key = decisionContext.stableKey + "|" + decision.reason + "|" + decision.matched + "|" + decision.excluded;
        long now = System.currentTimeMillis();
        synchronized (recentDiagnosticKeys) {
            Long previous = recentDiagnosticKeys.get(key);
            if (previous != null && now - previous < 120_000L) {
                return false;
            }
            recentDiagnosticKeys.put(key, now);
            return true;
        }
    }

    private String preview(String value) {
        if (value == null) {
            return "\"\"";
        }
        String normalized = value.replace('\n', ' ').trim();
        if (normalized.length() > 48) {
            normalized = normalized.substring(0, 48) + "...";
        }
        return "\"" + normalized + "\"";
    }

    private static final class DecisionContext {
        final FilterConfig config;
        final MessageSnapshot snapshot;
        final FilterDecision decision;
        final String stableKey;

        DecisionContext(FilterConfig config, MessageSnapshot snapshot, FilterDecision decision) {
            this.config = config;
            this.snapshot = snapshot;
            this.decision = decision;
            this.stableKey = snapshot == null ? "" : snapshot.stableKey();
        }

        static DecisionContext allow() {
            return new DecisionContext(null, null, FilterDecision.allow());
        }
    }

    private void injectMessageBlockMenu(Object chatActivity, View messageView) {
        injectMessageBlockMenu(chatActivity, messageView, null);
    }

    private void injectMessageBlockMenu(Object chatActivity, View messageView, Object preferredMessageObject) {
        if (chatActivity == null || messageView == null) {
            return;
        }
        Object popupWindow = Reflect.field(chatActivity, "scrimPopupWindow");
        Object contentView = Reflect.invokeIfExists(popupWindow, "getContentView", new Class<?>[0]);
        if (!(contentView instanceof View) || hasTaggedChild((View) contentView, MENU_ID_BLOCK_MESSAGE)) {
            return;
        }

        Object messageObject = preferredMessageObject;
        if (messageObject == null) {
            messageObject = Reflect.field(chatActivity, "selectedObject");
        }
        if (messageObject == null) {
            messageObject = resolveMessageObject(messageView);
        }
        if (messageObject == null) {
            return;
        }

        View blockItem = createMessageBlockMenuItem(((View) contentView).getContext(), chatActivity);
        if (blockItem == null) {
            return;
        }
        View smartItem = createSmartBlockMenuItem(((View) contentView).getContext(), chatActivity);
        if (smartItem != null) {
            smartItem.setTag(R.id.gramsieve_menu_item_id, MENU_ID_SMART_BLOCK);
            Object selectedMessageObjectForSmart = messageObject;
            smartItem.setOnClickListener(v -> {
                dismissScrimPopup(chatActivity);
                showSmartBlockConfirmation(v.getContext(), messageView, selectedMessageObjectForSmart);
            });
        }
        Object selectedMessageObject = messageObject;
        blockItem.setTag(R.id.gramsieve_menu_item_id, MENU_ID_BLOCK_MESSAGE);
        blockItem.setOnClickListener(v -> {
            dismissScrimPopup(chatActivity);
            addRuleForSelectedMessage(v.getContext(), messageView, selectedMessageObject);
        });

        View markItem = createMessageMarkMenuItem(((View) contentView).getContext(), chatActivity);
        if (markItem != null) {
            markItem.setTag(R.id.gramsieve_menu_item_id, MENU_ID_MARK_MESSAGE);
            markItem.setOnClickListener(v -> {
                dismissScrimPopup(chatActivity);
                markSelectedMessage(v.getContext(), chatActivity, selectedMessageObject);
            });
        }
        View editHistoryItem = resolveSelectedEditHistory(chatActivity, selectedMessageObject) != null
                ? createEditHistoryMenuItem(((View) contentView).getContext(), chatActivity)
                : null;
        if (editHistoryItem != null) {
            editHistoryItem.setTag(R.id.gramsieve_menu_item_id, MENU_ID_EDIT_HISTORY);
            editHistoryItem.setOnClickListener(v -> {
                dismissScrimPopup(chatActivity);
                showSelectedMessageEditHistory(v, chatActivity, selectedMessageObject);
            });
        }

        MenuInsertionPoint insertionPoint = findReportInsertionPoint((View) contentView);
        if (insertionPoint != null) {
            insertionPoint.parent.addView(
                    blockItem,
                    Math.min(insertionPoint.index + 1, insertionPoint.parent.getChildCount())
            );
            if (smartItem != null) {
                insertionPoint.parent.addView(
                        smartItem,
                        Math.min(insertionPoint.index + 2, insertionPoint.parent.getChildCount())
                );
            }
            if (markItem != null) {
                insertionPoint.parent.addView(
                        markItem,
                        Math.min(insertionPoint.index + (smartItem != null ? 3 : 2), insertionPoint.parent.getChildCount())
                );
            }
            if (editHistoryItem != null) {
                insertionPoint.parent.addView(
                        editHistoryItem,
                        Math.min(insertionPoint.index + (smartItem != null ? 4 : 3), insertionPoint.parent.getChildCount())
                );
            }
        } else {
            ViewGroup fallbackContainer = resolvePopupLinearLayout(contentView);
            if (fallbackContainer == null) {
                return;
            }
            fallbackContainer.addView(blockItem);
            if (smartItem != null) {
                fallbackContainer.addView(smartItem);
            }
            if (markItem != null) {
                fallbackContainer.addView(markItem);
            }
            if (editHistoryItem != null) {
                fallbackContainer.addView(editHistoryItem);
            }
        }
        refreshMessagePopup((View) contentView, blockItem, popupWindow);
    }

    private ViewGroup resolvePopupLinearLayout(Object contentView) {
        if (!(contentView instanceof ViewGroup)) {
            return null;
        }
        // Look for ActionBarPopupWindowLayout in children
        ViewGroup group = (ViewGroup) contentView;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child.getClass().getSimpleName().equals("ActionBarPopupWindowLayout")) {
                return (ViewGroup) child;
            }
        }
        Object linearLayout = Reflect.field(contentView, "linearLayout");
        if (linearLayout instanceof ViewGroup) {
            return (ViewGroup) linearLayout;
        }
        return group;
    }

    private View createMessageBlockMenuItem(Context context, Object chatActivity) {
        try {
            ClassLoader classLoader = chatActivity.getClass().getClassLoader();
            Class<?> itemClass = classLoader.loadClass("org.telegram.ui.ActionBar.ActionBarMenuSubItem");
            Object themeDelegate = Reflect.field(chatActivity, "themeDelegate");
            View item;
            if (themeDelegate != null) {
                Constructor<?> constructor = itemClass.getConstructor(
                        Context.class,
                        boolean.class,
                        boolean.class,
                        classLoader.loadClass("org.telegram.ui.ActionBar.Theme$ResourcesProvider")
                );
                item = (View) constructor.newInstance(context, false, true, themeDelegate);
            } else {
                Constructor<?> constructor = itemClass.getConstructor(Context.class, boolean.class, boolean.class);
                item = (View) constructor.newInstance(context, false, true);
            }
            CharSequence label = localizedBlockMessageLabel(context);
            int iconRes = resolveBlockMessageIcon(context);
            Reflect.invokeIfExists(
                    item,
                    "setTextAndIcon",
                    new Class<?>[]{CharSequence.class, int.class},
                    label,
                    iconRes
            );
            Reflect.invokeIfExists(item, "setText", new Class<?>[]{CharSequence.class}, label);
            item.setMinimumWidth(dp(context, 160f));
            return item;
        } catch (Throwable throwable) {
            error("Failed to create block-message menu item", throwable);
            return null;
        }
    }

    private View createSmartBlockMenuItem(Context context, Object chatActivity) {
        // Reuse the block-item construction so the smart item looks identical
        // to the other message-menu items in Conso.
        try {
            ClassLoader classLoader = chatActivity.getClass().getClassLoader();
            Class<?> itemClass = classLoader.loadClass("org.telegram.ui.ActionBar.ActionBarMenuSubItem");
            Object themeDelegate = Reflect.field(chatActivity, "themeDelegate");
            View item;
            if (themeDelegate != null) {
                Constructor<?> constructor = itemClass.getConstructor(
                        Context.class,
                        boolean.class,
                        boolean.class,
                        classLoader.loadClass("org.telegram.ui.ActionBar.Theme$ResourcesProvider")
                );
                item = (View) constructor.newInstance(context, false, true, themeDelegate);
            } else {
                Constructor<?> constructor = itemClass.getConstructor(Context.class, boolean.class, boolean.class);
                item = (View) constructor.newInstance(context, false, true);
            }
            CharSequence label = localizedSmartBlockLabel(context);
            int iconRes = resolveBlockMessageIcon(context);
            Reflect.invokeIfExists(
                    item,
                    "setTextAndIcon",
                    new Class<?>[]{CharSequence.class, int.class},
                    label,
                    iconRes
            );
            Reflect.invokeIfExists(item, "setText", new Class<?>[]{CharSequence.class}, label);
            item.setMinimumWidth(dp(context, 160f));
            return item;
        } catch (Throwable throwable) {
            error("Failed to create smart-block menu item", throwable);
            return null;
        }
    }

    private void showSmartBlockConfirmation(Context context, View messageView, Object messageObject) {
        info("Smart-block menu clicked");
        MessageSnapshot snapshot = TelegramMessageNormalizer.normalize(messageView, messageObject);
        if (snapshot == null) {
            Toast.makeText(context, localizedNoTextToast(context), Toast.LENGTH_SHORT).show();
            return;
        }
        FilterConfig existing = configProvider.getConfig(context);
        List<FilterConfig.RuleSpec> candidates = new ArrayList<>();
        for (FilterConfig.RuleSpec rule : MessageRuleFactory.smartRules(snapshot)) {
            if (rule != null && !MessageRuleFactory.containsEquivalentRule(existing.globalRules, rule)) {
                candidates.add(rule);
            }
        }
        if (candidates.isEmpty()) {
            Toast.makeText(
                    context,
                    isChineseLocale(context) ? "这条消息没有可提取的过滤词，或已全部在全局规则里" : "No new filter terms found for this message",
                    Toast.LENGTH_SHORT
            ).show();
            return;
        }
        // Pluggable AI backend (Build #19): local candidates are already
        // built above. If the user enabled the LLM backend, call it on a
        // background thread (never on the UI thread, never for automatic
        // filtering), merge its suggestions into the local candidates,
        // then show the same confirmation dialog. LLM failure never blocks
        // the dialog: with fallback on (default) the local candidates are
        // shown, with a toast noting the LLM failed.
        if (!LlmAiAnalyzer.isLlmEnabled(existing)) {
            showSmartBlockDialog(context, messageView, candidates, snapshot.dialogId);
            return;
        }
        Activity loadingActivity = findActivity(context);
        if (loadingActivity == null || loadingActivity.isFinishing()) {
            showSmartBlockDialog(context, messageView, candidates, snapshot.dialogId);
            return;
        }
        final List<FilterConfig.RuleSpec> localCandidates = new ArrayList<>(candidates);
        final FilterConfig llmConfig = existing;
        final MessageSnapshot llmSnapshot = snapshot;
        android.app.AlertDialog loadingDialog = new android.app.AlertDialog.Builder(loadingActivity)
                .setMessage(isChineseLocale(context) ? "AI 分析中…" : "AI analyzing…")
                .setCancelable(false)
                .create();
        loadingDialog.show();
        styleSystemDialog(loadingDialog, context);
        new Thread(() -> {
            LlmAiAnalyzer.Result llmResult = LlmAiAnalyzer.analyze(
                    llmConfig, llmSnapshot.combinedVisibleContent());
            List<FilterConfig.RuleSpec> merged = new ArrayList<>(localCandidates);
            if (llmResult != null && llmResult.isGambling && !llmResult.keywords.isEmpty()) {
                for (String keyword : llmResult.keywords) {
                    FilterConfig.RuleSpec rule = new FilterConfig.RuleSpec();
                    rule.mode = FilterConfig.RuleMode.KEYWORD;
                    rule.target = FilterConfig.RuleTarget.TEXT;
                    rule.pattern = keyword;
                    rule.caseSensitive = false;
                    rule = rule.sanitize();
                    if (rule != null
                            && !MessageRuleFactory.containsEquivalentRule(llmConfig.globalRules, rule)
                            && !MessageRuleFactory.containsEquivalentRule(merged, rule)) {
                        merged.add(rule);
                    }
                }
            }
            final List<FilterConfig.RuleSpec> finalCandidates = merged;
            final boolean llmFailed = llmResult == null || llmResult.error != null && !llmResult.error.isEmpty();
            loadingActivity.runOnUiThread(() -> {
                try {
                    loadingDialog.dismiss();
                } catch (Throwable ignored) {}
                if (loadingActivity.isFinishing()) {
                    return;
                }
                if (llmFailed && llmConfig.aiFallbackToLocal) {
                    Toast.makeText(context, isChineseLocale(context) ? "AI 分析失败，已用本地规则" : "AI analysis failed, using local rules", Toast.LENGTH_SHORT).show();
                }
                if (finalCandidates.isEmpty()) {
                    Toast.makeText(context, isChineseLocale(context) ? "这条消息没有可提取的过滤词，或已全部在全局规则里" : "No new filter terms found for this message", Toast.LENGTH_SHORT).show();
                    return;
                }
                showSmartBlockDialog(context, messageView, finalCandidates, llmSnapshot.dialogId);
            });
        }).start();
    }

    private Activity findActivity(Context context) {
        Context current = context;
        while (current instanceof ContextWrapper) {
            if (current instanceof Activity) {
                return (Activity) current;
            }
            current = ((ContextWrapper) current).getBaseContext();
        }
        return null;
    }

    private void showSmartBlockDialog(Context context, View messageView, List<FilterConfig.RuleSpec> candidates, long smartDialogId) {
        // Confirmation dialog with per-term checkboxes. If the host context
        // cannot show a dialog (not an Activity), fall back to adding all
        // candidates directly so the feature still works instead of crashing.
        try {
            Activity activity = null;
            Context current = context;
            while (current instanceof ContextWrapper) {
                if (current instanceof Activity) {
                    activity = (Activity) current;
                    break;
                }
                current = ((ContextWrapper) current).getBaseContext();
            }
            if (activity == null || activity.isFinishing()) {
                throw new IllegalStateException("No Activity context for smart-block dialog");
            }
            String[] labels = new String[candidates.size()];
            List<String> keywordTerms = new ArrayList<>();
            List<String> otherTerms = new ArrayList<>();
            for (int i = 0; i < candidates.size(); i++) {
                FilterConfig.RuleSpec rule = candidates.get(i);
                String targetLabel;
                if (rule.pattern != null && rule.pattern.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) {
                    targetLabel = "IP 地址";
                } else if (rule.target == FilterConfig.RuleTarget.SENDER) {
                    targetLabel = "发送者";
                } else if (rule.pattern != null && rule.pattern.contains(".")) {
                    targetLabel = "域名/链接";
                } else {
                    targetLabel = "关键词";
                }
                labels[i] = targetLabel + "：" + rule.pattern;
                if ("关键词".equals(targetLabel)) {
                    keywordTerms.add(rule.pattern);
                } else {
                    otherTerms.add(targetLabel + "：" + rule.pattern);
                }
            }
            // Strong terms are pre-checked; weak generic terms (体育 / 电子 /
            // 红包) are offered unchecked, so confirming by habit does not
            // block normal sports, e-goods or red-packet chats.
            boolean[] checked = new boolean[candidates.size()];
            for (int i = 0; i < candidates.size(); i++) {
                checked[i] = !FilterEngine.isWeakGamblingTerm(candidates.get(i).pattern);
            }
            // 2026-10-25 redesign for Build #7 feedback "smart analysis
            // does not show the analysed keywords": the old dialog used
            // setMessage + setMultiChoiceItems together, and on some
            // hosts the message (and the keyword summary) was swallowed,
            // leaving only bare checkboxes. Now the dialog has an explicit
            // analysis summary at the top, in a custom view, followed by
            // the same multi-choice list, so the analysed keywords are
            // always visible before confirming.
            // 2026-10-25 UI optimisation: group by category, use the
            // same visual language as the settings panel (13sp bold
            // accent section labels, 15sp item text, accent checkboxes),
            // instead of the system multiple-choice list which looked
            // nothing like the rest of the module. Summary stays on top
            // so the analysed keywords are always visible before confirm.
            String summary;
            if (isChineseLocale(context)) {
                StringBuilder sb = new StringBuilder();
                sb.append("分析出的关键词：");
                sb.append(keywordTerms.isEmpty() ? "无" : String.join("、", keywordTerms));
                if (!otherTerms.isEmpty()) {
                    sb.append("\n其他标识：").append(String.join("、", otherTerms));
                }
                summary = sb.toString();
            } else {
                summary = "Analysed keywords: " + (keywordTerms.isEmpty() ? "none" : String.join(", ", keywordTerms));
            }
            // Build #30: background and text from the same Telegram
            // theme pair, contrast-guarded, and painted onto the
            // custom content itself (the ScrollView/dialogView), not
            // just the window, which is what the old code missed and
            // why the 23:03 screenshot showed blank checkboxes.
            int smartBackgroundColor = dialogBackgroundColor(activity);
            int smartPrimaryColor = ensureDialogTextColor(dialogPrimaryColor(activity), smartBackgroundColor);
            int smartSecondaryColor = ensureDialogTextColor(dialogSecondaryColor(activity), smartBackgroundColor);
            LinearLayout dialogView = new LinearLayout(activity);
            dialogView.setOrientation(LinearLayout.VERTICAL);
            // Build #34 fix: the content layer itself must also stay
            // transparent, otherwise this opaque slab covers the glass
            // dialog window completely and only its edges show.
            dialogView.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            int pad = dp(context, 16f);
            // Build #32: rows carry the inset themselves (see
            // styleGroupRows below), so the container has no side
            // padding of its own.
            dialogView.setPadding(0, pad, 0, 0);
            TextView summaryView = new TextView(activity);
            summaryView.setTag("gramsieve_info");
            summaryView.setText(summary);
            summaryView.setTextColor(smartPrimaryColor);
            summaryView.setTextSize(14f);
            try { summaryView.setLineSpacing(dp(context, 2f), 1.15f); } catch (Throwable ignored) {}
            dialogView.addView(summaryView, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            // Group candidate indexes by category, in a fixed order, so
            // the list reads as sections instead of one flat checkbox list.
            java.util.Map<String, List<Integer>> groups = new java.util.LinkedHashMap<>();
            for (int i = 0; i < candidates.size(); i++) {
                String category = labels[i].contains("：")
                        ? labels[i].substring(0, labels[i].indexOf("："))
                        : "关键词";
                groups.computeIfAbsent(category, k -> new ArrayList<>()).add(i);
            }
            List<android.widget.CheckBox> checkBoxes = new ArrayList<>();
            for (int i = 0; i < candidates.size(); i++) {
                checkBoxes.add(null);
            }
            int accent = 0xFF3390EC;
            try {
                Class<?> themeClass = Class.forName("org.telegram.ui.ActionBar.Theme", false, activity.getClassLoader());
                java.lang.reflect.Field keyField = themeClass.getDeclaredField("key_dialogButton");
                keyField.setAccessible(true);
                java.lang.reflect.Method getColor = themeClass.getDeclaredMethod("getColor", int.class);
                getColor.setAccessible(true);
                Object color = getColor.invoke(null, keyField.getInt(null));
                if (color instanceof Integer) {
                    accent = (Integer) color;
                }
            } catch (Throwable ignored) {}
            // Quick actions: select all / strong-only, same accent text
            // style as the settings panel's toolbar buttons.
            LinearLayout actionRow = new LinearLayout(activity);
            actionRow.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            actionParams.topMargin = dp(context, 12f);
            actionRow.setLayoutParams(actionParams);
            android.widget.Button selectAll = new android.widget.Button(activity);
            selectAll.setAllCaps(false);
            selectAll.setText("全选");
            selectAll.setTextColor(accent);
            selectAll.setTextSize(14f);
            selectAll.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            selectAll.setOnClickListener(v -> {
                for (android.widget.CheckBox box : checkBoxes) {
                    if (box != null) {
                        box.setChecked(true);
                    }
                }
            });
            actionRow.addView(selectAll, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            android.widget.Button strongOnly = new android.widget.Button(activity);
            strongOnly.setAllCaps(false);
            strongOnly.setText("仅强信号");
            strongOnly.setTextColor(accent);
            strongOnly.setTextSize(14f);
            strongOnly.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            strongOnly.setOnClickListener(v -> {
                for (int i = 0; i < candidates.size(); i++) {
                    if (checkBoxes.get(i) != null) {
                        checkBoxes.get(i).setChecked(checked[i]);
                    }
                }
            });
            actionRow.addView(strongOnly, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            dialogView.addView(actionRow);
            for (java.util.Map.Entry<String, List<Integer>> entry : groups.entrySet()) {
                TextView sectionLabel = new TextView(activity);
                sectionLabel.setTag("gramsieve_section");
                sectionLabel.setText(entry.getKey());
                sectionLabel.setTextColor(accent);
                sectionLabel.setTextSize(13f);
                sectionLabel.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
                LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                labelParams.topMargin = dp(context, 12f);
                labelParams.bottomMargin = dp(context, 4f);
                dialogView.addView(sectionLabel, labelParams);
                for (int idx : entry.getValue()) {
                    LinearLayout row = new LinearLayout(activity);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(android.view.Gravity.CENTER_VERTICAL);
                    android.widget.CheckBox box = new android.widget.CheckBox(activity);
                    box.setChecked(checked[idx]);
                    try {
                        int[][] states = new int[][]{{android.R.attr.state_checked}, {}};
                        int[] colors = new int[]{accent, 0xFF8A8A8A};
                        box.setButtonTintList(new android.content.res.ColorStateList(states, colors));
                    } catch (Throwable ignored) {}
                    checkBoxes.set(idx, box);
                    row.addView(box, new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                    TextView itemText = new TextView(activity);
                    itemText.setText(candidates.get(idx).pattern);
                    itemText.setTextColor(smartPrimaryColor);
                    itemText.setTextSize(15f);
                    row.addView(itemText, new LinearLayout.LayoutParams(
                            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                    row.setOnClickListener(v -> box.setChecked(!box.isChecked()));
                    dialogView.addView(row, new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                }
            }
            // Target selector (Build #17): smart analysis no longer only
            // adds to global rules. The user chooses per dialog: global
            // (all chats) or current chat only (this dialogId). Default
            // stays global to preserve existing behaviour; chat target is
            // disabled when the snapshot has no dialogId.
            // smartDialogId is now a parameter (Build #19 fix: the old
            // method body referenced snapshot, which is out of scope here
            // after the LLM refactor split the dialog out).
            TextView targetLabel = new TextView(activity);
            targetLabel.setTag("gramsieve_section");
            targetLabel.setText("添加到");
            targetLabel.setTextColor(accent);
            targetLabel.setTextSize(13f);
            targetLabel.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
            LinearLayout.LayoutParams targetLabelParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            targetLabelParams.topMargin = dp(context, 12f);
            targetLabelParams.bottomMargin = dp(context, 4f);
            dialogView.addView(targetLabel, targetLabelParams);
            android.widget.RadioGroup targetGroup = new android.widget.RadioGroup(activity);
            targetGroup.setOrientation(android.widget.RadioGroup.VERTICAL);
            android.widget.RadioButton globalRadio = new android.widget.RadioButton(activity);
            globalRadio.setText("全局规则（所有聊天）");
            globalRadio.setTextColor(smartPrimaryColor);
            globalRadio.setTextSize(15f);
            globalRadio.setChecked(true);
            targetGroup.addView(globalRadio, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            android.widget.RadioButton chatRadio = new android.widget.RadioButton(activity);
            chatRadio.setText(smartDialogId == 0L ? "当前聊天规则（仅本聊天，无法识别当前聊天）" : "当前聊天规则（仅本聊天）");
            chatRadio.setTextColor(smartPrimaryColor);
            chatRadio.setTextSize(15f);
            chatRadio.setChecked(false);
            chatRadio.setEnabled(smartDialogId != 0L);
            targetGroup.addView(chatRadio, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            dialogView.addView(targetGroup, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            TextView footer = new TextView(activity);
            footer.setTag("gramsieve_info");
            footer.setText("已勾选的是强信号词；未勾选的是较通用词，可自行勾选。确认后加入上面所选的规则范围。");
            footer.setTextColor(smartSecondaryColor);
            footer.setTextSize(13f);
            LinearLayout.LayoutParams footerParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            footerParams.topMargin = dp(context, 12f);
            dialogView.addView(footer, footerParams);
            // Build #32: smart dialog rows use the same grouped
            // system as the settings panel: uniform row padding and
            // inset dividers between rows, labels attached to their rows.
            DialogTheme.styleGroupRows(dialogView, DialogTheme.resolve(activity), activity);
            ScrollView scrollView = new ScrollView(activity);
            // Build #34: transparent, so the glass dialog window
            // (styleSystemDialog after show) is the card, not a second
            // opaque slab stacked on top of it.
            scrollView.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            scrollView.addView(dialogView);
            AlertDialog smartDialog = new AlertDialog.Builder(activity)
                    .setTitle(localizedSmartBlockDialogTitle(context) + "（" + candidates.size() + " 个建议）")
                    .setView(scrollView)
                    .setPositiveButton(
                            isChineseLocale(context) ? "确认加入" : "Confirm",
                            (dialog, which) -> {
                                List<FilterConfig.RuleSpec> selected = new ArrayList<>();
                                for (int i = 0; i < candidates.size(); i++) {
                                    if (checkBoxes.get(i) != null && checkBoxes.get(i).isChecked()) {
                                        selected.add(candidates.get(i));
                                    }
                                }
                                if (chatRadio.isChecked() && smartDialogId != 0L) {
                                    addSmartBlockChatRules(context, messageView, selected, smartDialogId);
                                } else {
                                    addSmartBlockGlobalRules(context, messageView, selected);
                                }
                            }
                    )
                    .setNegativeButton(
                            isChineseLocale(context) ? "取消" : "Cancel",
                            null
                    )
                    .create();
            smartDialog.show();
            styleSystemDialog(smartDialog, context);
        } catch (Throwable throwable) {
            error("Smart-block dialog failed, adding all candidates directly", throwable);
            addSmartBlockGlobalRules(context, messageView, candidates);
        }
    }

    private void addSmartBlockGlobalRules(Context context, View messageView, List<FilterConfig.RuleSpec> selected) {
        if (selected == null || selected.isEmpty()) {
            Toast.makeText(context, localizedNoTextToast(context), Toast.LENGTH_SHORT).show();
            return;
        }
        FilterConfig updated = configProvider.getConfig(context).deepCopy();
        updated.enabled = true;
        int added = 0;
        for (FilterConfig.RuleSpec rule : selected) {
            if (!MessageRuleFactory.containsEquivalentRule(updated.globalRules, rule)) {
                updated.globalRules.add(rule);
                added++;
            }
        }
        updated.sanitize();
        updated.updatedAtEpochMs = System.currentTimeMillis();
        updated = saveUpdatedConfig(context, updated);
        decisionCache.clear();
        if (messageView != null) {
            refreshFilteringAround(messageView);
        }
        info("Smart-block added global rules added=" + added + " selected=" + selected.size());
        Toast.makeText(
                context,
                isChineseLocale(context) ? "已加入全局规则 " + added + " 条" : "Added " + added + " global rules",
                Toast.LENGTH_SHORT
        ).show();
    }

    private void addSmartBlockChatRules(Context context, View messageView, List<FilterConfig.RuleSpec> selected, long dialogId) {
        if (selected == null || selected.isEmpty()) {
            Toast.makeText(context, localizedNoTextToast(context), Toast.LENGTH_SHORT).show();
            return;
        }
        if (dialogId == 0L) {
            addSmartBlockGlobalRules(context, messageView, selected);
            return;
        }
        FilterConfig updated = configProvider.getConfig(context).deepCopy();
        FilterConfig.ChatRuleSet chatRuleSet = updated.getOrCreateChatRuleSet(dialogId);
        chatRuleSet.enabled = true;
        int added = 0;
        for (FilterConfig.RuleSpec rule : selected) {
            if (!MessageRuleFactory.containsEquivalentRule(chatRuleSet.rules, rule)) {
                chatRuleSet.rules.add(rule);
                added++;
            }
        }
        updated.sanitize();
        updated.updatedAtEpochMs = System.currentTimeMillis();
        updated = saveUpdatedConfig(context, updated);
        decisionCache.clear();
        if (messageView != null) {
            refreshFilteringAround(messageView);
        }
        info("Smart-block added chat rules dialogId=" + dialogId + " added=" + added + " selected=" + selected.size());
        Toast.makeText(
                context,
                isChineseLocale(context) ? "已加入当前聊天规则 " + added + " 条" : "Added " + added + " chat rules",
                Toast.LENGTH_SHORT
        ).show();
    }

    private int resolveBlockMessageIcon(Context context) {
        int telegramIcon = context.getResources().getIdentifier("report", "drawable", context.getPackageName());
        return telegramIcon != 0 ? telegramIcon : android.R.drawable.ic_menu_close_clear_cancel;
    }

    private View createMessageMarkMenuItem(Context context, Object chatActivity) {
        try {
            ClassLoader classLoader = chatActivity.getClass().getClassLoader();
            Class<?> itemClass = classLoader.loadClass("org.telegram.ui.ActionBar.ActionBarMenuSubItem");
            Object themeDelegate = Reflect.field(chatActivity, "themeDelegate");
            View item;
            if (themeDelegate != null) {
                Constructor<?> constructor = itemClass.getConstructor(
                        Context.class,
                        boolean.class,
                        boolean.class,
                        classLoader.loadClass("org.telegram.ui.ActionBar.Theme$ResourcesProvider")
                );
                item = (View) constructor.newInstance(context, false, true, themeDelegate);
            } else {
                Constructor<?> constructor = itemClass.getConstructor(Context.class, boolean.class, boolean.class);
                item = (View) constructor.newInstance(context, false, true);
            }
            CharSequence label = localizedMarkMessageLabel(context);
            int iconRes = resolveMarkMessageIcon(context);
            Reflect.invokeIfExists(
                    item,
                    "setTextAndIcon",
                    new Class<?>[]{CharSequence.class, int.class},
                    label,
                    iconRes
            );
            Reflect.invokeIfExists(item, "setText", new Class<?>[]{CharSequence.class}, label);
            item.setMinimumWidth(dp(context, 160f));
            return item;
        } catch (Throwable throwable) {
            error("Failed to create mark-message menu item", throwable);
            return null;
        }
    }

    private int resolveMarkMessageIcon(Context context) {
        int telegramIcon = context.getResources().getIdentifier("msg_message", "drawable", context.getPackageName());
        if (telegramIcon != 0) return telegramIcon;
        telegramIcon = context.getResources().getIdentifier("msg_bookmark", "drawable", context.getPackageName());
        return telegramIcon != 0 ? telegramIcon : android.R.drawable.ic_menu_save;
    }

    private View createEditHistoryMenuItem(Context context, Object chatActivity) {
        try {
            ClassLoader classLoader = chatActivity.getClass().getClassLoader();
            Class<?> itemClass = classLoader.loadClass("org.telegram.ui.ActionBar.ActionBarMenuSubItem");
            Object themeDelegate = Reflect.field(chatActivity, "themeDelegate");
            View item;
            if (themeDelegate != null) {
                Constructor<?> constructor = itemClass.getConstructor(
                        Context.class,
                        boolean.class,
                        boolean.class,
                        classLoader.loadClass("org.telegram.ui.ActionBar.Theme$ResourcesProvider")
                );
                item = (View) constructor.newInstance(context, false, true, themeDelegate);
            } else {
                Constructor<?> constructor = itemClass.getConstructor(Context.class, boolean.class, boolean.class);
                item = (View) constructor.newInstance(context, false, true);
            }
            CharSequence label = isChineseLocale(context) ? "编辑历史" : "Edit History";
            int iconRes = context.getResources().getIdentifier("msg_edit", "drawable", context.getPackageName());
            if (iconRes == 0) iconRes = android.R.drawable.ic_menu_edit;
            Reflect.invokeIfExists(
                    item,
                    "setTextAndIcon",
                    new Class<?>[]{CharSequence.class, int.class},
                    label,
                    iconRes
            );
            Reflect.invokeIfExists(item, "setText", new Class<?>[]{CharSequence.class}, label);
            item.setMinimumWidth(dp(context, 160f));
            return item;
        } catch (Throwable throwable) {
            error("Failed to create edit-history menu item", throwable);
            return null;
        }
    }

    private void showSelectedMessageEditHistory(View anchor, Object chatActivity, Object messageObject) {
        MessageCache.CachedMessage cached = resolveSelectedEditHistory(chatActivity, messageObject);
        if (cached == null) {
            Toast.makeText(anchor.getContext(), localizedNoEditHistory(anchor.getContext()), Toast.LENGTH_SHORT).show();
            return;
        }
        List<MessageCache.CachedMessage> history = messageCache != null
                ? messageCache.getEditHistory(cached.dialogId, cached.messageId)
                : java.util.Collections.emptyList();
        if (history.size() > 1) {
            showEditHistoryDialog(anchor, history, cached);
            return;
        }
        if (showOriginalMediaHistory(anchor, chatActivity, messageObject, cached)) {
            return;
        }
        showEditHistoryDialog(anchor, history, cached);
    }

    private MessageCache.CachedMessage resolveSelectedEditHistory(Object chatActivity, Object messageObject) {
        if (messageCache == null || messageObject == null) {
            return null;
        }
        long dialogId = Reflect.asLong(Reflect.invokeIfExists(messageObject, "getDialogId", new Class<?>[0]), 0L);
        if (dialogId == 0L) {
            dialogId = Reflect.asLong(Reflect.invokeIfExists(chatActivity, "getDialogId", new Class<?>[0]), 0L);
        }
        int messageId = resolveMessageId(messageObject);
        if (dialogId == 0L || messageId <= 0) {
            return null;
        }
        MessageCache.CachedMessage cached = messageCache.get(dialogId, messageId);
        if (hasRenderableEditHistory(cached)) {
            return cached;
        }
        List<MessageCache.CachedMessage> history = messageCache.getEditHistory(dialogId, messageId);
        return history.isEmpty() ? null : history.get(0);
    }

    private boolean showOriginalMediaHistory(View anchor, Object chatActivity, Object selectedMessageObject,
                                             MessageCache.CachedMessage cachedMessage) {
        Object mediaObject = messageCache != null
                ? messageCache.getMediaObject(cachedMessage.dialogId, cachedMessage.messageId)
                : null;
        java.io.File file = resolveCachedMediaFile(cachedMessage);
        if (file == null) {
            info("Anti-recall: original media history file missing msgId=" + cachedMessage.messageId
                    + " mediaType=" + cachedMessage.mediaType
                    + " mediaId=" + cachedMessage.mediaId
                    + " cachedPath=" + cachedMessage.cachedMediaPath
                    + " hasTelegramMedia=" + (mediaObject != null));
            if (mediaObject != null && mediaPrefetcher != null && selectedMessageObject != null) {
                mediaPrefetcher.prefetchFromMessage(
                        cachedMessage.dialogId,
                        cachedMessage.messageId,
                        selectedMessageObject,
                        mediaObject
                );
                Toast.makeText(anchor.getContext(), localizedMediaHistoryPreparing(anchor.getContext()), Toast.LENGTH_SHORT).show();
                return true;
            }
            if (!hasDisplayText(cachedMessage.text)
                    && !hasDisplayText(cachedMessage.caption)
                    && !hasDisplayText(cachedMessage.editedText)) {
                Toast.makeText(anchor.getContext(), localizedOriginalMediaUnavailable(anchor.getContext()), Toast.LENGTH_SHORT).show();
                return true;
            }
            return false;
        }
        if (mediaObject != null
                && showOriginalMediaMessageInPhotoViewer(anchor, chatActivity, selectedMessageObject, cachedMessage, file, mediaObject, false)) {
            return true;
        }
        if (!isVideoHistoryFile(cachedMessage, file)
                && showOriginalSyntheticPhotoInPhotoViewer(anchor, chatActivity, selectedMessageObject, cachedMessage, file)) {
            return true;
        }
        info("Anti-recall: using local media history preview msgId=" + cachedMessage.messageId
                + " hasTelegramMedia=" + (mediaObject != null));
        return showOriginalMediaDialog(anchor, cachedMessage, file);
    }

    private boolean hasRenderableEditHistory(MessageCache.CachedMessage cachedMessage) {
        if (cachedMessage == null || !cachedMessage.isEdited) {
            return false;
        }
        if (hasDisplayText(cachedMessage.text)
                || hasDisplayText(cachedMessage.caption)
                || hasDisplayText(cachedMessage.editedText)) {
            return true;
        }
        if (resolveCachedMediaFile(cachedMessage) != null) {
            return true;
        }
        return messageCache != null
                && messageCache.getMediaObject(cachedMessage.dialogId, cachedMessage.messageId) != null;
    }

    private static boolean hasDisplayText(String value) {
        return sanitizeDisplayText(value) != null;
    }

    private boolean showOriginalSyntheticPhotoInPhotoViewer(View anchor, Object chatActivity, Object selectedMessageObject,
                                                           MessageCache.CachedMessage cachedMessage, java.io.File file) {
        try {
            ClassLoader classLoader = resolveTelegramClassLoader(anchor.getContext(), chatActivity);
            Object mediaObject = createSyntheticHistoryPhotoMedia(classLoader, cachedMessage, file);
            boolean opened = showOriginalMediaMessageInPhotoViewer(
                    anchor,
                    chatActivity,
                    selectedMessageObject,
                    cachedMessage,
                    file,
                    mediaObject,
                    true
            );
            if (opened) {
                info("Anti-recall: opened original image history with synthetic Telegram photo msgId="
                        + cachedMessage.messageId + " file=" + file.getName());
            }
            return opened;
        } catch (Throwable throwable) {
            error("Anti-recall: synthetic Telegram photo open failed", throwable);
            return false;
        }
    }

    private boolean showOriginalMediaMessageInPhotoViewer(View anchor, Object chatActivity, Object selectedMessageObject,
                                                         MessageCache.CachedMessage cachedMessage, java.io.File file,
                                                         Object mediaObject, boolean isolateIdentity) {
        try {
            ClassLoader classLoader = resolveTelegramClassLoader(anchor.getContext(), chatActivity);
            if (mediaObject == null) {
                return false;
            }

            int account = resolveSelectedTelegramAccount(classLoader);
            Object message = createSyntheticHistoryMessage(classLoader, selectedMessageObject, cachedMessage, mediaObject, file, isolateIdentity);
            java.io.File telegramPath = syncHistoryFileToTelegramPath(classLoader, account, message, file);
            if (telegramPath == null) {
                return false;
            }
            Object messageObject = createTelegramMessageObject(classLoader, account, message);
            if (messageObject == null) {
                return false;
            }

            boolean opened = openTelegramPhotoViewerWithMessage(anchor, chatActivity, cachedMessage, messageObject, isolateIdentity);
            if (opened) {
                info("Anti-recall: opened original media history in official PhotoViewer msgId="
                        + cachedMessage.messageId + " file=" + file.getName()
                        + " isolated=" + isolateIdentity
                        + " telegramPath=" + telegramPath.getAbsolutePath());
            }
            return opened;
        } catch (Throwable throwable) {
            error("Anti-recall: official PhotoViewer message open failed", throwable);
            return false;
        }
    }

    private boolean showOriginalMediaDialog(View anchor, MessageCache.CachedMessage cachedMessage, java.io.File file) {
        if (isVideoHistoryFile(cachedMessage, file)) {
            return showOriginalVideoHistory(anchor, cachedMessage, file);
        }
        android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeFile(file.getAbsolutePath());
        if (bitmap == null) {
            return false;
        }
        Context context = anchor.getContext();
        android.app.Dialog dialog = new android.app.Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        android.widget.FrameLayout root = new android.widget.FrameLayout(context);
        root.setBackgroundColor(android.graphics.Color.BLACK);
        ImageView imageView = new ImageView(context);
        imageView.setImageBitmap(bitmap);
        imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        imageView.setAdjustViewBounds(true);
        root.addView(imageView, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        root.setOnClickListener(v -> dialog.dismiss());
        dialog.setContentView(root);
        dialog.show();
        info("Anti-recall: opened original image history locally msgId=" + cachedMessage.messageId + " file=" + file.getName());
        return true;
    }

    private boolean showOriginalVideoHistory(View anchor, MessageCache.CachedMessage cachedMessage, java.io.File file) {
        Context context = anchor.getContext();
        android.app.Dialog dialog = new android.app.Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        android.widget.FrameLayout root = new android.widget.FrameLayout(context);
        root.setBackgroundColor(android.graphics.Color.BLACK);

        android.widget.VideoView videoView = new android.widget.VideoView(context);
        android.widget.MediaController controller = new android.widget.MediaController(context);
        controller.setAnchorView(videoView);
        videoView.setMediaController(controller);
        videoView.setVideoPath(file.getAbsolutePath());
        root.addView(videoView, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        ImageButton closeButton = new ImageButton(context);
        closeButton.setImageResource(android.R.drawable.ic_menu_close_clear_cancel);
        closeButton.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        closeButton.setColorFilter(android.graphics.Color.WHITE);
        closeButton.setOnClickListener(v -> dialog.dismiss());
        android.widget.FrameLayout.LayoutParams closeParams = new android.widget.FrameLayout.LayoutParams(
                dp(context, 48f),
                dp(context, 48f),
                android.view.Gravity.TOP | android.view.Gravity.END
        );
        closeParams.topMargin = dp(context, 12f);
        closeParams.rightMargin = dp(context, 12f);
        root.addView(closeButton, closeParams);

        videoView.setOnPreparedListener(player -> {
            videoView.start();
            controller.show(3000);
        });
        videoView.setOnErrorListener((player, what, extra) -> {
            Toast.makeText(context, localizedNoEditHistory(context), Toast.LENGTH_SHORT).show();
            dialog.dismiss();
            return true;
        });
        dialog.setOnDismissListener(ignored -> videoView.stopPlayback());
        dialog.setContentView(root);
        dialog.show();
        info("Anti-recall: opened original video history locally msgId=" + cachedMessage.messageId + " file=" + file.getName());
        return true;
    }

    private ClassLoader resolveTelegramClassLoader(Context context, Object chatActivity) {
        if (savedClassLoader != null) {
            return savedClassLoader;
        }
        if (chatActivity != null && chatActivity.getClass().getClassLoader() != null) {
            return chatActivity.getClass().getClassLoader();
        }
        return context.getClassLoader();
    }

    private Object getTelegramPhotoViewer(ClassLoader classLoader) throws Throwable {
        Class<?> photoViewerClass = classLoader.loadClass("org.telegram.ui.PhotoViewer");
        Method getInstance = photoViewerClass.getMethod("getInstance");
        return getInstance.invoke(null);
    }

    private boolean setTelegramPhotoViewerParent(Object photoViewer, Object chatActivity) {
        if (photoViewer == null || chatActivity == null) {
            return false;
        }
        Object resourcesProvider = Reflect.field(chatActivity, "themeDelegate");
        for (Method method : photoViewer.getClass().getMethods()) {
            if (!method.getName().equals("setParentActivity") || method.getParameterCount() != 2 || resourcesProvider == null) {
                continue;
            }
            Class<?>[] types = method.getParameterTypes();
            if (types[0].isAssignableFrom(chatActivity.getClass()) && types[1].isAssignableFrom(resourcesProvider.getClass())) {
                try {
                    method.invoke(photoViewer, chatActivity, resourcesProvider);
                    return true;
                } catch (Throwable ignored) {
                }
            }
        }
        for (Method method : photoViewer.getClass().getMethods()) {
            if (!method.getName().equals("setParentActivity") || method.getParameterCount() != 1) {
                continue;
            }
            Class<?> type = method.getParameterTypes()[0];
            if (type.isAssignableFrom(chatActivity.getClass())) {
                try {
                    method.invoke(photoViewer, chatActivity);
                    return true;
                } catch (Throwable ignored) {
                }
            }
        }
        return false;
    }

    private Object createTelegramPhotoViewerProvider(ClassLoader classLoader) throws Throwable {
        Class<?> providerClass = classLoader.loadClass("org.telegram.ui.PhotoViewer$EmptyPhotoViewerProvider");
        Constructor<?> constructor = providerClass.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    private Object createSyntheticHistoryMessage(ClassLoader classLoader, Object selectedMessageObject,
                                                 MessageCache.CachedMessage cachedMessage, Object mediaObject,
                                                 java.io.File file, boolean isolateIdentity) throws Throwable {
        Class<?> messageClass = classLoader.loadClass("org.telegram.tgnet.TLRPC$TL_message");
        Object message = messageClass.getDeclaredConstructor().newInstance();
        Object selectedOwner = Reflect.field(selectedMessageObject, "messageOwner");
        Reflect.setField(message, "id", isolateIdentity ? syntheticHistoryMessageId(cachedMessage, file) : (int) cachedMessage.messageId);
        Reflect.setField(message, "date", (int) Math.max(0L, cachedMessage.timestamp / 1000L));
        Reflect.setField(message, "dialog_id", isolateIdentity ? 0L : cachedMessage.dialogId);
        Reflect.setField(message, "from_id", Reflect.field(selectedOwner, "from_id"));
        Reflect.setField(message, "peer_id", firstNonNull(Reflect.field(selectedOwner, "peer_id"), createPeerForDialogId(classLoader, cachedMessage.dialogId)));
        Reflect.setField(message, "message", firstNonEmpty(cachedMessage.text, cachedMessage.caption));
        Reflect.setField(message, "attachPath", file.getAbsolutePath());
        Reflect.setField(message, "media", mediaObject);
        return message;
    }

    private int syntheticHistoryMessageId(MessageCache.CachedMessage cachedMessage, java.io.File file) {
        long id = syntheticHistoryId(cachedMessage, file) & 0x3fffffffL;
        int value = (int) Math.max(1L, id);
        return -value;
    }

    private Object createSyntheticHistoryPhotoMedia(ClassLoader classLoader, MessageCache.CachedMessage cachedMessage,
                                                   java.io.File file) throws Throwable {
        int[] dimensions = readImageBounds(file);
        long id = syntheticHistoryId(cachedMessage, file);

        Object location = newTelegramObject(
                classLoader,
                "org.telegram.tgnet.TLRPC$TL_fileLocationToBeDeprecated",
                "org.telegram.tgnet.TLRPC$TL_fileLocation"
        );
        Reflect.setField(location, "volume_id", id);
        Reflect.setField(location, "local_id", (int) (id & 0x7fffffff));
        Reflect.setField(location, "secret", 0L);
        Reflect.setField(location, "dc_id", 0);
        Reflect.setField(location, "file_reference", new byte[0]);

        Object photoSize = newTelegramObject(classLoader, "org.telegram.tgnet.TLRPC$TL_photoSize");
        Reflect.setField(photoSize, "type", "x");
        Reflect.setField(photoSize, "w", Math.max(1, dimensions[0]));
        Reflect.setField(photoSize, "h", Math.max(1, dimensions[1]));
        Reflect.setField(photoSize, "size", (int) Math.min(Integer.MAX_VALUE, Math.max(1L, file.length())));
        Reflect.setField(photoSize, "location", location);

        ArrayList<Object> sizes = new ArrayList<>();
        sizes.add(photoSize);

        Object photo = newTelegramObject(classLoader, "org.telegram.tgnet.TLRPC$TL_photo");
        Reflect.setField(photo, "id", id);
        Reflect.setField(photo, "access_hash", 0L);
        Reflect.setField(photo, "file_reference", new byte[0]);
        Reflect.setField(photo, "date", (int) Math.max(0L, cachedMessage.timestamp / 1000L));
        Reflect.setField(photo, "sizes", sizes);
        Reflect.setField(photo, "dc_id", 0);
        Reflect.setField(photo, "has_stickers", false);

        Object media = newTelegramObject(classLoader, "org.telegram.tgnet.TLRPC$TL_messageMediaPhoto");
        Reflect.setField(media, "photo", photo);
        Reflect.setField(media, "caption", firstNonEmpty(cachedMessage.caption, cachedMessage.text));
        Reflect.setField(media, "ttl_seconds", 0);
        return media;
    }

    private Object newTelegramObject(ClassLoader classLoader, String... classNames) throws Throwable {
        Throwable last = null;
        for (String className : classNames) {
            try {
                Constructor<?> constructor = classLoader.loadClass(className).getDeclaredConstructor();
                constructor.setAccessible(true);
                return constructor.newInstance();
            } catch (Throwable throwable) {
                last = throwable;
            }
        }
        throw last != null ? last : new ClassNotFoundException("No Telegram class candidates");
    }

    private long syntheticHistoryId(MessageCache.CachedMessage cachedMessage, java.io.File file) {
        long hash = 1469598103934665603L;
        String key = "gramsieve-photo:" + cachedMessage.dialogId + ":" + cachedMessage.messageId
                + ":" + file.length() + ":" + file.getName();
        for (int i = 0; i < key.length(); i++) {
            hash ^= key.charAt(i);
            hash *= 1099511628211L;
        }
        hash &= Long.MAX_VALUE;
        return hash == 0L ? 1L : hash;
    }

    private Object createPeerForDialogId(ClassLoader classLoader, long dialogId) throws Throwable {
        long normalizedId = Math.abs(dialogId);
        String className = dialogId < 0 ? "org.telegram.tgnet.TLRPC$TL_peerChannel" : "org.telegram.tgnet.TLRPC$TL_peerUser";
        Object peer = classLoader.loadClass(className).getDeclaredConstructor().newInstance();
        if (dialogId < 0) {
            Reflect.setField(peer, "channel_id", normalizedId);
            Reflect.setField(peer, "chat_id", normalizedId);
        } else {
            Reflect.setField(peer, "user_id", normalizedId);
        }
        return peer;
    }

    private java.io.File syncHistoryFileToTelegramPath(ClassLoader classLoader, int account, Object message,
                                                       java.io.File sourceFile) throws Throwable {
        Class<?> fileLoaderClass = classLoader.loadClass("org.telegram.messenger.FileLoader");
        Class<?> messageBaseClass = classLoader.loadClass("org.telegram.tgnet.TLRPC$Message");
        Object fileLoader = Reflect.invokeStatic(fileLoaderClass, "getInstance", new Class<?>[]{int.class}, account);
        if (fileLoader == null) {
            return null;
        }
        Method getPathToMessage = fileLoaderClass.getMethod("getPathToMessage", messageBaseClass);
        java.io.File targetFile = (java.io.File) getPathToMessage.invoke(fileLoader, message);
        if (targetFile == null || targetFile.getPath().isEmpty()) {
            info("Anti-recall: FileLoader returned empty path for original history media");
            return null;
        }
        if (!sourceFile.getCanonicalPath().equals(targetFile.getCanonicalPath())
                && (!targetFile.exists() || targetFile.length() != sourceFile.length())) {
            copyFile(sourceFile, targetFile);
        }
        return targetFile.exists() && targetFile.length() > 0 ? targetFile : null;
    }

    private void copyFile(java.io.File sourceFile, java.io.File targetFile) throws java.io.IOException {
        java.io.File parent = targetFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new java.io.IOException("Failed to create " + parent);
        }
        try (java.io.FileInputStream input = new java.io.FileInputStream(sourceFile);
             java.io.FileOutputStream output = new java.io.FileOutputStream(targetFile)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
        }
    }

    private Object createTelegramMessageObject(ClassLoader classLoader, int account, Object message) throws Throwable {
        Class<?> messageObjectClass = classLoader.loadClass("org.telegram.messenger.MessageObject");
        Class<?> messageBaseClass = classLoader.loadClass("org.telegram.tgnet.TLRPC$Message");
        Constructor<?> constructor = messageObjectClass.getConstructor(int.class, messageBaseClass, boolean.class, boolean.class);
        return constructor.newInstance(account, message, false, true);
    }

    private boolean openTelegramPhotoViewerWithMessage(View anchor, Object chatActivity,
                                                      MessageCache.CachedMessage cachedMessage, Object messageObject,
                                                      boolean isolateIdentity) throws Throwable {
        ClassLoader classLoader = resolveTelegramClassLoader(anchor.getContext(), chatActivity);
        Object photoViewer = getTelegramPhotoViewer(classLoader);
        if (photoViewer == null || !setTelegramPhotoViewerParent(photoViewer, chatActivity)) {
            return false;
        }
        Object provider = createTelegramPhotoViewerProvider(classLoader);
        Class<?> messageObjectClass = classLoader.loadClass("org.telegram.messenger.MessageObject");
        Class<?> chatActivityClass = classLoader.loadClass("org.telegram.ui.ChatActivity");
        Class<?> providerClass = classLoader.loadClass("org.telegram.ui.PhotoViewer$PhotoViewerProvider");
        Method openPhoto = photoViewer.getClass().getMethod(
                "openPhoto",
                messageObjectClass,
                chatActivityClass,
                long.class,
                long.class,
                long.class,
                providerClass
        );
        Object result = openPhoto.invoke(photoViewer, messageObject, chatActivity, 0L, isolateIdentity ? 0L : cachedMessage.dialogId, 0L, provider);
        return Boolean.TRUE.equals(result);
    }

    private int resolveSelectedTelegramAccount(ClassLoader classLoader) {
        try {
            Class<?> userConfigClass = classLoader.loadClass("org.telegram.messenger.UserConfig");
            return Reflect.asInt(Reflect.staticField(userConfigClass, "selectedAccount"), 0);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private Object firstNonNull(Object first, Object second) {
        return first != null ? first : second;
    }

    private int[] readImageBounds(java.io.File file) {
        android.graphics.BitmapFactory.Options options = new android.graphics.BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        android.graphics.BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        return new int[]{Math.max(0, options.outWidth), Math.max(0, options.outHeight)};
    }

    private boolean isVideoHistoryFile(MessageCache.CachedMessage cachedMessage, java.io.File file) {
        String path = file.getName().toLowerCase(Locale.ROOT);
        String mediaType = cachedMessage.mediaType != null ? cachedMessage.mediaType.toLowerCase(Locale.ROOT) : "";
        return path.endsWith(".mp4") || path.endsWith(".m4v") || path.endsWith(".mov")
                || path.endsWith(".webm") || path.endsWith(".mkv") || mediaType.contains("video");
    }

    private java.io.File resolveCachedMediaFile(MessageCache.CachedMessage cachedMessage) {
        if (cachedMessage.cachedMediaPath != null) {
            java.io.File file = new java.io.File(cachedMessage.cachedMediaPath);
            if (file.exists() && file.length() > 0) {
                return file;
            }
        }
        if (mediaCache == null) {
            return null;
        }
        java.io.File file = mediaCache.getMedia(cachedMessage.dialogId, cachedMessage.messageId, ".jpg");
        if (file != null) return file;
        file = mediaCache.getMedia(cachedMessage.dialogId, cachedMessage.messageId, ".png");
        if (file != null) return file;
        file = mediaCache.getMedia(cachedMessage.dialogId, cachedMessage.messageId, ".webp");
        if (file != null) return file;
        file = mediaCache.getMedia(cachedMessage.dialogId, cachedMessage.messageId, ".gif");
        if (file != null) return file;
        file = mediaCache.getMedia(cachedMessage.dialogId, cachedMessage.messageId, ".mp4");
        if (file != null) return file;
        file = mediaCache.getMedia(cachedMessage.dialogId, cachedMessage.messageId, ".m4v");
        if (file != null) return file;
        file = mediaCache.getMedia(cachedMessage.dialogId, cachedMessage.messageId, ".mov");
        if (file != null) return file;
        file = mediaCache.getMedia(cachedMessage.dialogId, cachedMessage.messageId, ".webm");
        if (file != null) return file;
        file = mediaCache.getMedia(cachedMessage.dialogId, cachedMessage.messageId, ".mkv");
        if (file != null) return file;
        return mediaCache.getMedia(cachedMessage.dialogId, cachedMessage.messageId, ".bin");
    }

    private String localizedNoEditHistory(Context context) {
        return isChineseLocale(context) ? "无编辑历史" : "No edit history";
    }

    private String localizedMediaHistoryPreparing(Context context) {
        return isChineseLocale(context)
                ? "原始媒体仍在准备，请稍后再试"
                : "Original media is still being prepared. Try again shortly.";
    }

    private String localizedOriginalMediaUnavailable(Context context) {
        return isChineseLocale(context)
                ? "原始媒体未缓存"
                : "Original media was not cached";
    }

    private void markSelectedMessage(Context context, Object chatActivity, Object messageObject) {
        long dialogId = Reflect.asLong(Reflect.invokeIfExists(chatActivity, "getDialogId", new Class<?>[0]), 0L);
        int messageId = resolveMessageId(messageObject);
        if (dialogId == 0L || messageId <= 0) {
            return;
        }
        saveMarkedPosition(context.getApplicationContext(), dialogId, messageId);
        Toast.makeText(context, localizedMarkSavedToast(context), Toast.LENGTH_SHORT).show();
        info("Marked message " + messageId + " for dialog " + dialogId);
    }

    private void saveMarkedPosition(Context context, long dialogId, int messageId) {
        SharedPreferences prefs = context.getSharedPreferences("gramsieve_marked_positions", Context.MODE_PRIVATE);
        prefs.edit().putInt("marked_msg_" + dialogId, messageId).apply();
    }

    private int loadMarkedPosition(Context context, long dialogId) {
        SharedPreferences prefs = context.getSharedPreferences("gramsieve_marked_positions", Context.MODE_PRIVATE);
        return prefs.getInt("marked_msg_" + dialogId, 0);
    }

    private MenuInsertionPoint findReportInsertionPoint(View root) {
        if (!(root instanceof ViewGroup)) {
            return null;
        }
        ViewGroup group = (ViewGroup) root;
        List<String> reportLabels = reportLabels(root.getContext());
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (isTelegramMenuSubItem(child) && textMatchesAny(child, reportLabels)) {
                return new MenuInsertionPoint(group, i);
            }
        }
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            MenuInsertionPoint nested = findReportInsertionPoint(child);
            if (nested != null) {
                return nested;
            }
        }
        return null;
    }

    private boolean isTelegramMenuSubItem(View view) {
        return view != null && "org.telegram.ui.ActionBar.ActionBarMenuSubItem".equals(view.getClass().getName());
    }

    private List<String> reportLabels(Context context) {
        List<String> labels = new ArrayList<>();
        addTelegramString(labels, context, "Report2");
        addTelegramString(labels, context, "ReportMessagesNoCaps");
        addTelegramString(labels, context, "ReportSpamNoCaps");
        addTelegramString(labels, context, "DeleteReportSpam");
        addTelegramString(labels, context, "ProfileActionsReport");
        labels.add("Report");
        labels.add("Report Spam");
        labels.add("举报");
        return labels;
    }

    private void addTelegramString(List<String> labels, Context context, String name) {
        int id = context.getResources().getIdentifier(name, "string", context.getPackageName());
        if (id == 0) {
            return;
        }
        try {
            String label = context.getString(id).trim();
            if (!label.isBlank() && !labels.contains(label)) {
                labels.add(label);
            }
        } catch (Resources.NotFoundException ignored) {
        }
    }

    private boolean textMatchesAny(View view, List<String> labels) {
        if (view instanceof TextView) {
            String text = ((TextView) view).getText() == null ? "" : ((TextView) view).getText().toString().trim();
            for (String label : labels) {
                if (!label.isBlank() && (text.equals(label) || text.contains(label))) {
                    return true;
                }
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (textMatchesAny(group.getChildAt(i), labels)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean hasTaggedChild(View view, int targetId) {
        Object keyedTag = view.getTag(R.id.gramsieve_menu_item_id);
        if (keyedTag instanceof Integer && ((Integer) keyedTag) == targetId) {
            return true;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (hasTaggedChild(group.getChildAt(i), targetId)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void refreshMessagePopup(View popupContent, View insertedItem, Object popupWindow) {
        refreshRadialSelectors(insertedItem);
        popupContent.requestLayout();
        popupContent.invalidate();
        popupContent.post(() -> {
            refreshRadialSelectors(insertedItem);
            popupContent.requestLayout();
            popupContent.invalidate();
            if (popupWindow instanceof PopupWindow) {
                PopupWindow window = (PopupWindow) popupWindow;
                window.setHeight(ViewGroup.LayoutParams.WRAP_CONTENT);
                window.update();
            }
        });
        if (popupWindow instanceof PopupWindow) {
            PopupWindow window = (PopupWindow) popupWindow;
            window.setHeight(ViewGroup.LayoutParams.WRAP_CONTENT);
            window.update();
        }
    }

    private void refreshRadialSelectors(View view) {
        View current = view;
        while (current != null) {
            Reflect.invokeIfExists(current, "updateRadialSelectors", new Class<?>[0]);
            Object parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
    }

    private static final class MenuInsertionPoint {
        final ViewGroup parent;
        final int index;

        MenuInsertionPoint(ViewGroup parent, int index) {
            this.parent = parent;
            this.index = index;
        }
    }

    private void addRuleForSelectedMessage(Context context, View messageView, Object messageObject) {
        info("Block-message menu clicked");
        MessageSnapshot snapshot = TelegramMessageNormalizer.normalize(messageView, messageObject);
        List<FilterConfig.RuleSpec> rules = MessageRuleFactory.automaticRules(snapshot);
        if (snapshot == null || rules.isEmpty()) {
            Toast.makeText(context, localizedNoTextToast(context), Toast.LENGTH_SHORT).show();
            return;
        }
        FilterConfig updated = configProvider.getConfig(context).deepCopy();
        updated.enabled = true;
        FilterConfig.ChatRuleSet chatRuleSet = updated.getOrCreateChatRuleSet(snapshot.dialogId);
        chatRuleSet.enabled = true;
        int added = 0;
        for (FilterConfig.RuleSpec rule : rules) {
            if (!MessageRuleFactory.containsEquivalentRule(chatRuleSet.rules, rule)) {
                chatRuleSet.rules.add(rule);
                added++;
            }
        }
        updated.sanitize();
        updated.updatedAtEpochMs = System.currentTimeMillis();
        updated = saveUpdatedConfig(context, updated);
        FilterDecision decision = filterEngine.evaluate(updated, snapshot);
        decisionCache.clear();
        if (messageView != null) {
            UiMutation.apply(messageView, decision, snapshot.stableKey());
            messageView.requestLayout();
        }
        refreshFilteringAround(messageView);
        info(
                "Added block-message rules added=" + added
                        + " candidates=" + rules.size()
                        + " dialog=" + snapshot.dialogId
                        + " matchedNow=" + decision.matched
                        + " ruleId=" + decision.ruleId
                        + " updatedAt=" + updated.updatedAtEpochMs
        );
        Toast.makeText(context, localizedSavedToast(context), Toast.LENGTH_SHORT).show();
    }

    private FilterConfig saveUpdatedConfig(Context hostContext, FilterConfig updated) {
        FilterConfig saved = saveToRemotePreferences(updated);
        if (saved == null) {
            saved = saveToContentProvider(hostContext, updated);
        }
        if (saved != null) {
            updated = saved;
        }
        persistToModuleProcess(hostContext, updated);
        configProvider.replaceCachedConfig(updated);
        return updated;
    }

    private void persistToModuleProcess(Context hostContext, FilterConfig updated) {
        try {
            if (hostContext == null || updated == null) {
                return;
            }
            String encodedConfig = encodedConfig(updated);
            Intent activityIntent = new Intent();
            activityIntent.setComponent(new ComponentName(MODULE_PACKAGE, MODULE_PACKAGE + ".config.ConfigPersistActivity"));
            activityIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activityIntent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
            activityIntent.addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            activityIntent.putExtra(ConfigUpdateReceiver.EXTRA_CONFIG_JSON_BASE64, encodedConfig);
            hostContext.startActivity(activityIntent);
            info("Requested module-local config persistence activity updatedAt=" + updated.updatedAtEpochMs);

            Intent intent = new Intent(ConfigUpdateReceiver.ACTION_SAVE_CONFIG);
            intent.setComponent(new ComponentName(MODULE_PACKAGE, MODULE_PACKAGE + ".config.ConfigUpdateReceiver"));
            intent.putExtra(ConfigUpdateReceiver.EXTRA_CONFIG_JSON_BASE64, encodedConfig);
            hostContext.sendBroadcast(intent);
            info("Requested module-local config persistence broadcast updatedAt=" + updated.updatedAtEpochMs);
        } catch (RuntimeException exception) {
            ModuleLogger.warn(ModuleLogger.CAT_CONFIG, TAG, "Failed to request module-local config persistence: " + exception.getMessage());
        }
    }

    private String encodedConfig(FilterConfig config) {
        return Base64.encodeToString(
                ModuleConfigStore.toJson(config).getBytes(StandardCharsets.UTF_8),
                Base64.NO_WRAP
        );
    }

    private FilterConfig saveToRemotePreferences(FilterConfig updated) {
        try {
            if (updated == null) {
                return null;
            }
            updated.sanitize();
            if (updated.updatedAtEpochMs <= 0L) {
                updated.updatedAtEpochMs = System.currentTimeMillis();
            }
            android.content.SharedPreferences remotePreferences = module.getRemotePreferences(ModuleConfigStore.PREFS_NAME);
            if (remotePreferences == null) {
                return null;
            }
            boolean committed = remotePreferences.edit()
                    .putString(ModuleConfigStore.KEY_CONFIG_JSON, ModuleConfigStore.toJson(updated))
                    .commit();
            if (!committed) {
                ModuleLogger.warn(ModuleLogger.CAT_CONFIG, TAG, "Failed to save message rule through remote preferences: commit=false");
                return null;
            }
            String savedJson = remotePreferences.getString(ModuleConfigStore.KEY_CONFIG_JSON, null);
            FilterConfig saved = ModuleConfigStore.fromJson(savedJson);
            if (!sameConfigExceptTimestamp(updated, saved)) {
                ModuleLogger.warn(ModuleLogger.CAT_CONFIG, TAG, "Failed to save message rule through remote preferences: readback mismatch");
                return null;
            }
            info("Saved message rule through remote preferences updatedAt=" + saved.updatedAtEpochMs);
            return saved.deepCopy();
        } catch (RuntimeException exception) {
            ModuleLogger.warn(ModuleLogger.CAT_CONFIG, TAG, "Failed to save message rule through remote preferences: " + exception.getMessage());
            return null;
        }
    }

    private FilterConfig saveToContentProvider(Context hostContext, FilterConfig updated) {
        try {
            Bundle extras = new Bundle();
            extras.putString(ConfigContentProvider.KEY_CONFIG_JSON, ModuleConfigStore.toJson(updated));
            Bundle result = hostContext.getContentResolver().call(
                    ConfigContentProvider.CONTENT_URI,
                    ConfigContentProvider.METHOD_SAVE_CONFIG,
                    null,
                    extras
            );
            if (result == null) {
                return null;
            }
            String json = result.getString(ConfigContentProvider.KEY_CONFIG_JSON, null);
            FilterConfig saved = ModuleConfigStore.fromJson(json);
            long updatedAt = result.getLong(ConfigContentProvider.KEY_UPDATED_AT_EPOCH_MS, saved.updatedAtEpochMs);
            if (updatedAt > 0L) {
                saved.updatedAtEpochMs = updatedAt;
            }
            saved = saved.sanitize();
            if (!sameConfigExceptTimestamp(updated, saved)) {
                ModuleLogger.warn(ModuleLogger.CAT_CONFIG, TAG, "Failed to save message rule through content provider: readback mismatch");
                return null;
            }
            info("Saved message rule through content provider updatedAt=" + saved.updatedAtEpochMs);
            return saved;
        } catch (RuntimeException exception) {
            ModuleLogger.warn(ModuleLogger.CAT_CONFIG, TAG, "Failed to save message rule through content provider: " + exception.getMessage());
            return null;
        }
    }

    private boolean sameConfigExceptTimestamp(FilterConfig expected, FilterConfig actual) {
        if (expected == null || actual == null) {
            return false;
        }
        FilterConfig expectedCopy = expected.deepCopy().sanitize();
        FilterConfig actualCopy = actual.deepCopy().sanitize();
        actualCopy.updatedAtEpochMs = expectedCopy.updatedAtEpochMs;
        return ModuleConfigStore.toJson(expectedCopy).equals(ModuleConfigStore.toJson(actualCopy));
    }

    private void refreshFilteringAround(View anchor) {
        decisionCache.clear();
        if (anchor == null) {
            return;
        }
        View refreshRoot = findRefreshRoot(anchor);
        int refreshed = refreshBoundMessages(refreshRoot);
        refreshRoot.requestLayout();
        refreshRoot.invalidate();
        Object parent = refreshRoot.getParent();
        if (parent instanceof View) {
            ((View) parent).requestLayout();
        }
        refreshRoot.post(() -> {
            int postRefreshed = refreshBoundMessages(refreshRoot);
            refreshRoot.requestLayout();
            refreshRoot.invalidate();
            int postRemaining = refreshProbeBudget.getAndDecrement();
            if (postRemaining > 0) {
                info(
                        "Post refresh root=" + refreshRoot.getClass().getSimpleName()
                                + " refreshed=" + postRefreshed
                );
            }
        });
        int remaining = refreshProbeBudget.getAndDecrement();
        if (remaining > 0) {
            info(
                    "Immediate refresh root=" + refreshRoot.getClass().getSimpleName()
                            + " refreshed=" + refreshed
            );
        }
    }

    private void refreshChatActivityFiltering(Object chatActivity) {
        decisionCache.clear();
        View root = resolveChatActivityRoot(chatActivity);
        if (root == null) {
            return;
        }
        int refreshed = refreshBoundMessages(root);
        root.requestLayout();
        root.invalidate();
        int remaining = refreshProbeBudget.getAndDecrement();
        if (remaining > 0) {
            info(
                    "Resume refresh root=" + root.getClass().getSimpleName()
                            + " refreshed=" + refreshed
            );
        }
    }

    private View resolveChatActivityRoot(Object chatActivity) {
        Object fragmentView = Reflect.field(chatActivity, "fragmentView");
        if (fragmentView instanceof View) {
            return (View) fragmentView;
        }
        Object contentView = Reflect.invokeIfExists(chatActivity, "getFragmentView", new Class<?>[0]);
        if (contentView instanceof View) {
            return (View) contentView;
        }
        return null;
    }

    private View findRefreshRoot(View anchor) {
        View current = anchor;
        View best = anchor;
        while (current != null) {
            String className = current.getClass().getName();
            if (className.contains("RecyclerView")) {
                return current;
            }
            if (current instanceof ViewGroup) {
                best = current;
            }
            Object parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return best;
    }

    private View findRecyclerDirectChild(View descendant) {
        if (descendant == null) {
            return null;
        }
        View current = descendant;
        View child = descendant;
        while (current != null) {
            Object parent = current.getParent();
            if (!(parent instanceof View)) {
                return null;
            }
            View parentView = (View) parent;
            if (isLikelyRecyclerView(parentView)) {
                return child;
            }
            child = parentView;
            current = parentView;
        }
        return null;
    }

    private int refreshBoundMessages(View root) {
        if (root == null) {
            return 0;
        }
        Object messageObject = resolveMessageObject(root);
        if (messageObject != null) {
            applyDecision(root, root, messageObject);
            return 1;
        }
        if (!(root instanceof ViewGroup)) {
            return 0;
        }
        if (isLikelyRecyclerView(root)) {
            return refreshRecyclerRows((ViewGroup) root);
        }
        ViewGroup group = (ViewGroup) root;
        int refreshed = 0;
        for (int i = 0; i < group.getChildCount(); i++) {
            refreshed += refreshBoundMessages(group.getChildAt(i));
        }
        return refreshed;
    }

    private int refreshRecyclerRows(ViewGroup recyclerView) {
        int refreshed = 0;
        for (int i = 0; i < recyclerView.getChildCount(); i++) {
            View child = recyclerView.getChildAt(i);
            if (BoundMessageViewWalker.visit(child, (messageView, messageObject) -> {
            })) {
                applyDecisionToBoundViews(child);
                child.requestLayout();
                child.invalidate();
                refreshed++;
            }
        }
        return refreshed;
    }

    private boolean isLikelyRecyclerView(View view) {
        if (view == null) {
            return false;
        }
        Class<?> current = view.getClass();
        while (current != null) {
            if ("androidx.recyclerview.widget.RecyclerView".equals(current.getName())) {
                return true;
            }
            current = current.getSuperclass();
        }
        return view.getClass().getName().contains("RecyclerView");
    }

    private void dismissScrimPopup(Object chatActivity) {
        Object popupWindow = Reflect.field(chatActivity, "scrimPopupWindow");
        Reflect.invokeIfExists(popupWindow, "dismiss", new Class<?>[0]);
    }

    // Build #31: per-headerItem record of chat menu ids already
    // added. Weak keys so a discarded ChatActivity/headerItem can
    // be collected; a fresh headerItem starts empty and injects once.
    private final java.util.Map<Object, java.util.Set<Integer>> injectedChatMenuIds =
            new java.util.WeakHashMap<>();

    private void injectChatMenu(Object chatActivity) {
        Object headerItem = Reflect.field(chatActivity, "headerItem");
        if (headerItem == null) {
            return;
        }
        if (!hasMenuItem(headerItem, MENU_ID_CHAT) && !isChatMenuRecorded(headerItem, MENU_ID_CHAT)) {
            Context context = contextFromMenuItem(headerItem);
            int iconRes = resolveIcon(context);
            Object subItem = addChatMenuSubItem(chatActivity, headerItem, MENU_ID_CHAT, iconRes, localizedChatMenuLabel(context));
            if (subItem instanceof View) {
                View subItemView = (View) subItem;
                subItemView.setTag(R.id.gramsieve_menu_item_id, MENU_ID_CHAT);
                subItemView.setOnClickListener(v -> {
                    try {
                        long dialogId = Reflect.asLong(Reflect.invokeIfExists(chatActivity, "getDialogId", new Class<?>[0]), 0L);
                        String title = resolveChatTitle(chatActivity);
                        openConfigFromHost(chatActivity, v.getContext(), CONFIG_MODE_CHAT, dialogId, title);
                    } finally {
                        Reflect.invokeIfExists(headerItem, "toggleSubMenu", new Class<?>[0]);
                    }
                });
            } else {
                info("ChatActivity menu addSubItem unavailable on " + headerItem.getClass().getName());
            }
        }
        injectScrollToTopMenu(chatActivity, headerItem);
        injectJumpToMarkMenu(chatActivity, headerItem);
        injectAntiRecallMenu(chatActivity, headerItem);
        injectCleanupModeMenu(chatActivity, headerItem);
        injectAddToFolderMenu(chatActivity, headerItem);
    }

    private void beginReadPositionTracking(Object chatActivity) {
        long dialogId = Reflect.asLong(Reflect.invokeIfExists(chatActivity, "getDialogId", new Class<?>[0]), 0L);
        trackedDialogId = dialogId;
        lastTopmostMessageId = 0;
        readPositionDirty = false;
        jumpDetected = false;
    }

    private void flushReadPosition(Object chatActivity) {
        long dialogId = trackedDialogId;
        int messageId = lastTopmostMessageId;
        boolean dirty = readPositionDirty;
        boolean jumped = jumpDetected;
        trackedDialogId = 0L;
        lastTopmostMessageId = 0;
        readPositionDirty = false;
        jumpDetected = false;
        info("FlushReadPos: dialogId=" + dialogId + " msgId=" + messageId + " dirty=" + dirty + " jumped=" + jumped);
        if (dialogId == 0L || messageId <= 0) {
            return;
        }
        if (dirty && !jumped) {
            Context context = resolveContextFromActivity(chatActivity);
            if (context != null) {
                ChatReadPositionStore.save(context.getApplicationContext(), dialogId, messageId);
                info("FlushReadPos: saved " + messageId + " for dialog " + dialogId);
            }
        }
    }

    private void trackTopmostMessage(View cell) {
        long dialogId = trackedDialogId;
        if (dialogId == 0L) {
            return;
        }
        if (cell.getTop() > 0) {
            return;
        }
        Object parent = cell.getParent();
        if (parent == null) {
            return;
        }
        Object messageObject = resolveMessageObject(cell);
        if (messageObject == null) {
            return;
        }
        int messageId = resolveMessageId(messageObject);
        if (messageId <= 0) {
            return;
        }
        if (messageId != lastTopmostMessageId) {
            int oldId = lastTopmostMessageId;
            if (oldId > 0 && messageId > oldId && (messageId - oldId) >= SCROLL_JUMP_THRESHOLD) {
                ChatReadPositionStore.save(cell.getContext(), dialogId, oldId);
                jumpDetected = true;
                info("JumpDetected: saved old position " + oldId + " before jump to " + messageId + " (delta=" + (messageId - oldId) + ") dialog=" + dialogId);
            }
            lastTopmostMessageId = messageId;
            readPositionDirty = true;
        }
    }

    private int resolveMessageId(Object messageObject) {
        Object directId = Reflect.invokeIfExists(messageObject, "getId", new Class<?>[0]);
        if (directId instanceof Integer) {
            return (Integer) directId;
        }
        Object owner = Reflect.field(messageObject, "messageOwner");
        if (owner != null) {
            Object ownerId = Reflect.field(owner, "id");
            if (ownerId instanceof Integer) {
                return (Integer) ownerId;
            }
        }
        return 0;
    }

    private Context resolveContextFromActivity(Object chatActivity) {
        Object fragmentView = Reflect.field(chatActivity, "fragmentView");
        if (fragmentView instanceof View) {
            return ((View) fragmentView).getContext();
        }
        Object contentView = Reflect.invokeIfExists(chatActivity, "getFragmentView", new Class<?>[0]);
        if (contentView instanceof View) {
            return ((View) contentView).getContext();
        }
        return null;
    }

    private void injectScrollToTopMenu(Object chatActivity, Object headerItem) {
        if (hasMenuItem(headerItem, MENU_ID_SCROLL_TOP) || isChatMenuRecorded(headerItem, MENU_ID_SCROLL_TOP)) {
            return;
        }
        Context context = contextFromMenuItem(headerItem);
        int iconRes = resolveScrollTopIcon(context);
        Object subItem = addChatMenuSubItem(chatActivity, headerItem, MENU_ID_SCROLL_TOP, iconRes, localizedScrollTopLabel(context));
        if (!(subItem instanceof View)) {
            info("Scroll-to-top addSubItem unavailable on " + headerItem.getClass().getName());
            return;
        }
        View subItemView = (View) subItem;
        subItemView.setTag(R.id.gramsieve_menu_item_id, MENU_ID_SCROLL_TOP);
        subItemView.setOnClickListener(v -> {
            try {
                info("ScrollToTop menu clicked");
                Reflect.invokeIfExists(headerItem, "toggleSubMenu", new Class<?>[0]);
                scrollChatToTop(chatActivity, v.getContext());
            } catch (Throwable throwable) {
                error("Scroll to top failed", throwable);
            }
        });
    }

    private void injectJumpToMarkMenu(Object chatActivity, Object headerItem) {
        if (hasMenuItem(headerItem, MENU_ID_JUMP_TO_MARK) || isChatMenuRecorded(headerItem, MENU_ID_JUMP_TO_MARK)) {
            return;
        }
        Context context = contextFromMenuItem(headerItem);
        int iconRes = resolveJumpToMarkIcon(context);
        Object subItem = addChatMenuSubItem(chatActivity, headerItem, MENU_ID_JUMP_TO_MARK, iconRes, localizedJumpToMarkLabel(context));
        if (!(subItem instanceof View)) {
            info("Jump-to-mark addSubItem unavailable on " + headerItem.getClass().getName());
            return;
        }
        View subItemView = (View) subItem;
        subItemView.setTag(R.id.gramsieve_menu_item_id, MENU_ID_JUMP_TO_MARK);
        subItemView.setOnClickListener(v -> {
            try {
                info("JumpToMark menu clicked");
                Reflect.invokeIfExists(headerItem, "toggleSubMenu", new Class<?>[0]);
                jumpToMarkedPosition(chatActivity, v.getContext());
            } catch (Throwable throwable) {
                error("Jump to mark failed", throwable);
            }
        });
    }

    private void injectAntiRecallMenu(Object chatActivity, Object headerItem) {
        if (backgroundMessageLoader == null) {
            // Try deferred initialization with chat context
            initAntiRecallFromChat(chatActivity);
        }
        if (backgroundMessageLoader == null) {
            info("Anti-recall: backgroundMessageLoader is null after init");
            return;
        }
        if (hasMenuItem(headerItem, MENU_ID_ANTI_RECALL) || isChatMenuRecorded(headerItem, MENU_ID_ANTI_RECALL)) {
            return;
        }
        if (backgroundMessageLoader == null) {
            info("Anti-recall: backgroundMessageLoader is null after deferred init");
            return;
        }
        if (hasMenuItem(headerItem, MENU_ID_ANTI_RECALL)) {
            info("Anti-recall: menu item already exists");
            return;
        }
        Context context = contextFromMenuItem(headerItem);
        long dialogId = Reflect.asLong(Reflect.invokeIfExists(chatActivity, "getDialogId", new Class<?>[0]), 0L);

        // Debug: check all possible ID fields
        Object currentChat = Reflect.field(chatActivity, "currentChat");
        long chatId = currentChat != null ? Reflect.asLong(Reflect.field(currentChat, "id"), 0L) : 0L;
        long channelId = currentChat != null ? Reflect.asLong(Reflect.field(currentChat, "channel_id"), 0L) : 0L;
        info("Anti-recall: dialogId=" + dialogId + " chatId=" + chatId + " channelId=" + channelId);

        info("Anti-recall: injecting menu for dialogId=" + dialogId);
        int iconRes = resolveAntiRecallIcon(context);
        CharSequence label = antiRecallStatusLabel(context, dialogId);
        info("Anti-recall: label=" + label);
        Object subItem = addChatMenuSubItem(chatActivity, headerItem, MENU_ID_ANTI_RECALL, iconRes, label);
        if (!(subItem instanceof View)) {
            info("Anti-recall addSubItem unavailable on " + headerItem.getClass().getName());
            return;
        }
        View subItemView = (View) subItem;
        subItemView.setTag(R.id.gramsieve_menu_item_id, MENU_ID_ANTI_RECALL);
        info("Anti-recall: menu item created, class=" + subItemView.getClass().getName());
        subItemView.setOnClickListener(v -> {
            try {
                info("Anti-recall: onClick fired");
                boolean wasEnabled = backgroundMessageLoader.isChatEnabled(dialogId);
                info("Anti-recall: wasEnabled=" + wasEnabled + " dialogId=" + dialogId);
                if (wasEnabled) {
                    backgroundMessageLoader.disableChat(dialogId);
                } else {
                    backgroundMessageLoader.enableChat(dialogId);
                }
                boolean nowEnabled = backgroundMessageLoader.isChatEnabled(dialogId);
                info("Anti-recall: nowEnabled=" + nowEnabled);
                CharSequence newLabel = antiRecallStatusLabel(v.getContext(), dialogId);
                info("Anti-recall: newLabel=" + newLabel);
                Reflect.invokeIfExists(v, "setText", new Class<?>[]{CharSequence.class}, newLabel);
                info("Anti-recall: setText called");
            } catch (Throwable throwable) {
                error("Anti-recall click failed", throwable);
            }
        });
        info("Anti-recall: menu injection complete");
    }

    private int resolveAntiRecallIcon(Context context) {
        int telegramIcon = context.getResources().getIdentifier("msg_message", "drawable", context.getPackageName());
        return telegramIcon != 0 ? telegramIcon : android.R.drawable.ic_menu_save;
    }

    private CharSequence antiRecallStatusLabel(Context context, long dialogId) {
        boolean enabled = backgroundMessageLoader.isChatEnabled(dialogId);
        if (isChineseLocale(context)) {
            return enabled ? "主动加载已打开" : "主动加载未打开";
        }
        return enabled ? "Proactive Loading: ON" : "Proactive Loading: OFF";
    }

    private void injectAddToFolderMenu(Object chatActivity, Object headerItem) {
        if (hasMenuItem(headerItem, MENU_ID_ADD_TO_FOLDER) || isChatMenuRecorded(headerItem, MENU_ID_ADD_TO_FOLDER)) {
            return;
        }
        long dialogId = Reflect.asLong(Reflect.invokeIfExists(chatActivity, "getDialogId", new Class<?>[0]), 0L);
        if (dialogId == 0L) {
            return;
        }
        // Build #31: was broadcast-only, so groups never saw the
        // item. Now any chat/channel dialog qualifies (broadcast
        // channels, megagroups, basic groups); private 1:1 chats
        // (positive dialogId, no currentChat) still do not.
        if (!isGroupChat(chatActivity, dialogId)) {
            return;
        }
        Context context = contextFromMenuItem(headerItem);
        int iconRes = resolveAddToFolderIcon(context);
        Object subItem = addChatMenuSubItem(chatActivity, headerItem, MENU_ID_ADD_TO_FOLDER, iconRes, localizedAddToFolderLabel(context));
        if (!(subItem instanceof View)) {
            info("Add-to-folder addSubItem unavailable on " + headerItem.getClass().getName());
            return;
        }
        View subItemView = (View) subItem;
        subItemView.setTag(R.id.gramsieve_menu_item_id, MENU_ID_ADD_TO_FOLDER);
        subItemView.setOnClickListener(v -> {
            try {
                Reflect.invokeIfExists(headerItem, "toggleSubMenu", new Class<?>[0]);
                showAddToFolderDialog(chatActivity, v.getContext(), dialogId);
            } catch (Throwable throwable) {
                error("Add-to-folder menu click failed", throwable);
            }
        });
    }

    private boolean isGroupChat(Object chatActivity, long dialogId) {
        try {
            Object currentChat = Reflect.field(chatActivity, "currentChat");
            if (currentChat != null) {
                return true;
            }
            if (dialogId < 0) {
                Object controller = messagesControllerForChat(chatActivity);
                if (controller != null) {
                    Object chat = Reflect.invokeIfExists(controller, "getChat", new Class<?>[]{Long.class}, Long.valueOf(-dialogId));
                    if (chat == null) {
                        chat = Reflect.invokeIfExists(controller, "getChat", new Class<?>[]{long.class}, -dialogId);
                    }
                    if (chat != null) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private Object messagesControllerForChat(Object chatActivity) {
        try {
            int account = Reflect.asInt(Reflect.field(chatActivity, "currentAccount"), -1);
            if (account < 0 && savedClassLoader != null) {
                account = resolveSelectedTelegramAccount(savedClassLoader);
            }
            if (account < 0) {
                account = 0;
            }
            ClassLoader cl = savedClassLoader != null ? savedClassLoader : chatActivity.getClass().getClassLoader();
            Class<?> mcClass = cl.loadClass("org.telegram.messenger.MessagesController");
            return Reflect.invokeStatic(mcClass, "getInstance", new Class<?>[]{int.class}, account);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void showAddToFolderDialog(Object chatActivity, Context context, long dialogId) {
        try {
            Activity activity = findActivity(context);
            if (activity == null || activity.isFinishing()) {
                Toast.makeText(context, localizedAddToFolderLabel(context), Toast.LENGTH_SHORT).show();
                return;
            }
            Object controller = messagesControllerForChat(chatActivity);
            if (controller == null) {
                Toast.makeText(context, localizedAddToFolderFailed(context), Toast.LENGTH_SHORT).show();
                return;
            }
            Object filtersObj = Reflect.invokeIfExists(controller, "getDialogFilters", new Class<?>[0]);
            if (!(filtersObj instanceof List)) {
                Toast.makeText(context, localizedNoFolder(context), Toast.LENGTH_LONG).show();
                return;
            }
            List<Object> filters = new ArrayList<>();
            List<String> names = new ArrayList<>();
            for (Object filter : (List<?>) filtersObj) {
                String name = Reflect.asString(Reflect.field(filter, "name"));
                if (name == null || name.isBlank()) {
                    continue;
                }
                // Build #23: ALL_CHATS is Telegram's built-in "All Chats"
                // pseudo-filter, not a real user folder; listing it let the
                // user "add" a channel to All Chats, which is meaningless.
                if ("ALL_CHATS".equalsIgnoreCase(name.trim())) {
                    continue;
                }
                filters.add(filter);
                names.add(name);
            }
            if (filters.isEmpty()) {
                Toast.makeText(context, localizedNoFolder(context), Toast.LENGTH_LONG).show();
                return;
            }
            String[] items = names.toArray(new String[0]);
            // Build #23 panel style (user screenshot 2026-10-25: the plain
            // system dialog was white on a dark host). The adapter sets
            // the item text colour on every getView, so recycled rows stay
            // readable too, unlike a one-time post-show recolour. Window
            // background, title and cancel button colours are set after
            // show(), when those views exist.
            int folderPrimaryColor = ensureDialogTextColor(
                    dialogPrimaryColor(activity), dialogBackgroundColor(activity));
            android.widget.ArrayAdapter<String> folderAdapter =
                    new android.widget.ArrayAdapter<String>(activity, android.R.layout.simple_list_item_1, items) {
                        @Override
                        public View getView(int position, View convertView, ViewGroup parent) {
                            View view = super.getView(position, convertView, parent);
                            if (view instanceof TextView) {
                                ((TextView) view).setTextColor(folderPrimaryColor);
                            }
                            return view;
                        }
                    };
            AlertDialog folderDialog = new AlertDialog.Builder(activity)
                    .setTitle(localizedAddToFolderLabel(context))
                    .setAdapter(folderAdapter, (dialog, which) -> {
                        try {
                            Object filter = filters.get(which);
                            int filterId = Reflect.asInt(Reflect.field(filter, "id"), -1);
                            if (filterId < 0) {
                                Toast.makeText(context, localizedAddToFolderFailed(context), Toast.LENGTH_SHORT).show();
                                return;
                            }
                            // Build #40: addDialogToFolder is the host's
                            // own official call, but it applies
                            // asynchronously (server round-trip), so an
                            // immediate re-read always missed it and the
                            // old code then wrote the membership a second
                            // time itself — two writers, one official and
                            // one local, fighting over the same filter.
                            // Trust the host call first; verify after a
                            // delay, and only then fall back, at most
                            // once, on a freshly re-read filter object.
                            Reflect.invokeIfExists(controller, "addDialogToFolder",
                                    new Class<?>[]{long.class, int.class, int.class, long.class},
                                    dialogId, filterId, 0, 0L);
                            final Object verifyController = controller;
                            final long verifyDialogId = dialogId;
                            final int verifyFilterId = filterId;
                            final String verifyFolderName = items[which];
                            final android.content.Context verifyContext = context;
                            final android.app.Activity verifyActivity = activity;
                            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                                try {
                                    boolean verified = folderHasDialog(verifyController, verifyFilterId, verifyDialogId);
                                    if (!verified) {
                                        try {
                                            Object freshFilters = Reflect.invokeIfExists(verifyController, "getDialogFilters", new Class<?>[0]);
                                            if (freshFilters instanceof List) {
                                                for (Object next : (List<?>) freshFilters) {
                                                    if (Reflect.asInt(Reflect.field(next, "id"), -999) == verifyFilterId) {
                                                        Object members = Reflect.field(next, "alwaysShow");
                                                        if (!(members instanceof List)) {
                                                            members = Reflect.field(next, "dialogs");
                                                        }
                                                        if (members instanceof List && !((List<?>) members).contains(verifyDialogId)) {
                                                            ((List) members).add(verifyDialogId);
                                                        }
                                                        Object neverShow = Reflect.field(next, "neverShow");
                                                        if (neverShow instanceof List) {
                                                            ((List<?>) neverShow).remove(verifyDialogId);
                                                        }
                                                        Reflect.invokeIfExists(verifyController, "updateFilterDialogs",
                                                                new Class<?>[]{next.getClass()}, next);
                                                        Reflect.invokeIfExists(verifyController, "onFilterUpdate",
                                                                new Class<?>[]{next.getClass()}, next);
                                                        break;
                                                    }
                                                }
                                            }
                                        } catch (Throwable ignored) {
                                        }
                                        verified = folderHasDialog(verifyController, verifyFilterId, verifyDialogId);
                                        info("AddToFolder: host did not apply, fallback written, verified=" + verified);
                                    } else {
                                        info("AddToFolder: host applied, verified=true folderId=" + verifyFilterId);
                                    }
                                    if (verifyActivity != null && !verifyActivity.isFinishing()) {
                                        if (verified) {
                                            Toast.makeText(verifyContext, localizedAddedToFolder(verifyContext, verifyFolderName), Toast.LENGTH_SHORT).show();
                                        } else {
                                            Toast.makeText(verifyContext, localizedAddToFolderFailed(verifyContext), Toast.LENGTH_SHORT).show();
                                        }
                                    }
                                } catch (Throwable ignored) {
                                }
                            }, 2000L);
                        } catch (Throwable throwable) {
                            error("Add-to-folder failed", throwable);
                            Toast.makeText(context, localizedAddToFolderFailed(context), Toast.LENGTH_SHORT).show();
                        }
                    })
                    .setNegativeButton(localizedCancel(context), null)
                    .create();
            folderDialog.show();
            // Build #32: same system styling as every other dialog;
            // the adapter above still sets each row's text colour.
            styleSystemDialog(folderDialog, activity);
        } catch (Throwable throwable) {
            error("Add-to-folder dialog failed", throwable);
            Toast.makeText(context, localizedAddToFolderFailed(context), Toast.LENGTH_SHORT).show();
        }
    }

    private boolean folderHasDialog(Object controller, int filterId, long dialogId) {
        try {
            Object fresh = Reflect.invokeIfExists(controller, "getDialogFilters", new Class<?>[0]);
            if (fresh instanceof List) {
                for (Object next : (List<?>) fresh) {
                    if (Reflect.asInt(Reflect.field(next, "id"), -999) == filterId) {
                        return folderMemberDialogIds(next).contains(dialogId);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private int resolveAddToFolderIcon(Context context) {
        if (context == null) {
            return android.R.drawable.ic_menu_add;
        }
        int telegramIcon = context.getResources().getIdentifier("msg_folder", "drawable", context.getPackageName());
        return telegramIcon != 0 ? telegramIcon : android.R.drawable.ic_menu_add;
    }

    private CharSequence localizedAddToFolderLabel(Context context) {
        return isChineseLocale(context) ? "将当前频道添加至文件夹" : "Add current channel to folder";
    }

    private CharSequence localizedNoFolder(Context context) {
        return isChineseLocale(context) ? "还没有文件夹，请先在聊天列表里创建文件夹" : "No folders yet. Create a folder in the chat list first.";
    }

    private CharSequence localizedAddedToFolder(Context context, String folderName) {
        return isChineseLocale(context) ? "已添加到文件夹：" + folderName : "Added to folder: " + folderName;
    }

    private CharSequence localizedAddToFolderFailed(Context context) {
        return isChineseLocale(context) ? "添加到文件夹失败" : "Failed to add to folder";
    }

    private CharSequence localizedCancel(Context context) {
        return isChineseLocale(context) ? "取消" : "Cancel";
    }

    private void hookTranslatedLinkRestore(ClassLoader classLoader) {
        try {
            Class<?> moClass = classLoader.loadClass("org.telegram.messenger.MessageObject");
            for (Method candidate : moClass.getDeclaredMethods()) {
                if (!"updateTranslation".equals(candidate.getName())) {
                    continue;
                }
                candidate.setAccessible(true);
                Method method = candidate;
                hook(method, chain -> {
                    Object result = chain.proceed();
                    try {
                        restoreLinksInTranslatedText(chain.getThisObject());
                    } catch (Throwable ignored) {
                    }
                    return result;
                });
            }
            info("Enhancement: hooked MessageObject.updateTranslation for translated-link restore");
        } catch (Throwable throwable) {
            error("Enhancement: failed to hook MessageObject.updateTranslation", throwable);
        }
    }

    private void restoreLinksInTranslatedText(Object messageObject) {
        if (messageObject == null) {
            return;
        }
        restoreLinksInField(messageObject, "messageText");
        restoreLinksInField(messageObject, "caption");
    }

    private void restoreLinksInField(Object messageObject, String fieldName) {
        try {
            Object textObj = Reflect.field(messageObject, fieldName);
            if (!(textObj instanceof CharSequence)) {
                return;
            }
            CharSequence text = (CharSequence) textObj;
            String plain = text.toString();
            if (plain == null || plain.isEmpty()) {
                return;
            }
            java.util.regex.Pattern urlPattern = java.util.regex.Pattern.compile(
                    "(https?://[^\\s\\u4e00-\\u9fff]+|t\\.me/[^\\s]+|www\\.[^\\s]+)");
            java.util.regex.Matcher matcher = urlPattern.matcher(plain);
            List<int[]> ranges = new ArrayList<>();
            List<String> urls = new ArrayList<>();
            while (matcher.find()) {
                String raw = matcher.group(1);
                while (!raw.isEmpty() && ".,;:!?)}]".indexOf(raw.charAt(raw.length() - 1)) >= 0) {
                    raw = raw.substring(0, raw.length() - 1);
                }
                if (raw.isEmpty()) {
                    continue;
                }
                int start = matcher.start(1);
                int end = start + raw.length();
                boolean covered = false;
                if (text instanceof android.text.Spanned) {
                    Object[] spans = ((android.text.Spanned) text).getSpans(
                            start, end, android.text.style.ClickableSpan.class);
                    if (spans != null && spans.length > 0) {
                        covered = true;
                    }
                }
                if (!covered) {
                    ranges.add(new int[]{start, end});
                    urls.add(raw);
                }
            }
            if (ranges.isEmpty()) {
                return;
            }
            android.text.SpannableStringBuilder builder;
            if (text instanceof android.text.SpannableStringBuilder) {
                builder = (android.text.SpannableStringBuilder) text;
            } else {
                builder = new android.text.SpannableStringBuilder(text);
            }
            ClassLoader cl = messageObject.getClass().getClassLoader();
            Class<?> spanClass = null;
            try {
                spanClass = cl.loadClass("org.telegram.ui.Components.URLSpanNoUnderline");
            } catch (Throwable ignored) {
            }
            for (int i = 0; i < ranges.size(); i++) {
                String raw = urls.get(i);
                String url = raw.startsWith("t.me/") || raw.startsWith("www.") ? "https://" + raw : raw;
                Object span = null;
                try {
                    if (spanClass != null) {
                        java.lang.reflect.Constructor<?> ctor = spanClass.getConstructor(String.class);
                        span = ctor.newInstance(url);
                    }
                } catch (Throwable ignored) {
                }
                if (span == null) {
                    span = new android.text.style.URLSpan(url);
                }
                builder.setSpan(span, ranges.get(i)[0], ranges.get(i)[1],
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            Reflect.setField(messageObject, fieldName, builder);
        } catch (Throwable ignored) {
        }
    }

    private void injectCleanupModeMenu(Object chatActivity, Object headerItem) {
        if (recallDetector == null) {
            initAntiRecallFromChat(chatActivity);
        }
        if (recallDetector == null || hasMenuItem(headerItem, MENU_ID_CLEANUP_MODE) || isChatMenuRecorded(headerItem, MENU_ID_CLEANUP_MODE)) {
            return;
        }
        Context context = contextFromMenuItem(headerItem);
        long dialogId = Reflect.asLong(Reflect.invokeIfExists(chatActivity, "getDialogId", new Class<?>[0]), 0L);
        if (dialogId == 0L) {
            return;
        }
        int iconRes = resolveCleanupModeIcon(context);
        Object subItem = addChatMenuSubItem(chatActivity, headerItem, MENU_ID_CLEANUP_MODE, iconRes,
                cleanupModeStatusLabel(context, dialogId));
        if (!(subItem instanceof View)) {
            info("CleanupMode addSubItem unavailable on " + headerItem.getClass().getName());
            return;
        }
        View subItemView = (View) subItem;
        subItemView.setTag(R.id.gramsieve_menu_item_id, MENU_ID_CLEANUP_MODE);
        subItemView.setOnClickListener(v -> {
            try {
                boolean enabled = recallDetector.toggleCleanupMode(dialogId, CLEANUP_MODE_DURATION_MS);
                Reflect.invokeIfExists(v, "setText", new Class<?>[]{CharSequence.class},
                        cleanupModeStatusLabel(v.getContext(), dialogId));
                Toast.makeText(v.getContext(), localizedCleanupModeToast(v.getContext(), enabled),
                        Toast.LENGTH_SHORT).show();
                info("CleanupMode: toggled dialogId=" + dialogId + " enabled=" + enabled);
            } catch (Throwable throwable) {
                error("Cleanup mode click failed", throwable);
            }
        });
    }

    private int resolveCleanupModeIcon(Context context) {
        int telegramIcon = context.getResources().getIdentifier("msg_delete", "drawable", context.getPackageName());
        return telegramIcon != 0 ? telegramIcon : android.R.drawable.ic_menu_delete;
    }

    private CharSequence cleanupModeStatusLabel(Context context, long dialogId) {
        boolean enabled = recallDetector != null && recallDetector.isCleanupModeActive(dialogId);
        if (isChineseLocale(context)) {
            return enabled ? "清理模式已打开" : "清理模式未打开";
        }
        return enabled ? "Cleanup Mode: ON" : "Cleanup Mode: OFF";
    }

    private CharSequence localizedCleanupModeToast(Context context, boolean enabled) {
        if (isChineseLocale(context)) {
            return enabled ? "清理模式已打开，5 分钟内删除会放行" : "清理模式已关闭";
        }
        return enabled ? "Cleanup mode is on for 5 minutes" : "Cleanup mode is off";
    }

    private void jumpToMarkedPosition(Object chatActivity, Context context) {
        long dialogId = Reflect.asLong(Reflect.invokeIfExists(chatActivity, "getDialogId", new Class<?>[0]), 0L);
        int markedMessageId = loadMarkedPosition(context.getApplicationContext(), dialogId);
        if (markedMessageId <= 0) {
            Toast.makeText(context, localizedNoMarkToast(context), Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(context, localizedJumpToMarkStarted(context), Toast.LENGTH_SHORT).show();
        boolean invoked = invokeScrollToMessageId(chatActivity, markedMessageId);
        if (invoked) {
            info("Called scrollToMessageId(" + markedMessageId + ") for marked position");
        } else {
            info("scrollToMessageId failed for marked position, falling back to scrollToLastMessage");
            suppressNextSaveBeforeJump = true;
            try {
                Reflect.invokeIfExists(chatActivity, "scrollToLastMessage",
                        new Class<?>[]{boolean.class, boolean.class}, false, false);
            } finally {
                suppressNextSaveBeforeJump = false;
            }
        }
        Toast.makeText(context, localizedJumpToMarkDone(context), Toast.LENGTH_SHORT).show();
    }

    private int resolveJumpToMarkIcon(Context context) {
        int telegramIcon = context.getResources().getIdentifier("msg_go_down", "drawable", context.getPackageName());
        if (telegramIcon != 0) return telegramIcon;
        telegramIcon = context.getResources().getIdentifier("msg_arrow_down", "drawable", context.getPackageName());
        if (telegramIcon != 0) return telegramIcon;
        return android.R.drawable.ic_menu_mylocation;
    }

    private volatile boolean suppressNextSaveBeforeJump;

    private void scrollChatToTop(Object chatActivity, Context context) {
        long dialogId = Reflect.asLong(Reflect.invokeIfExists(chatActivity, "getDialogId", new Class<?>[0]), 0L);
        ChatReadPositionStore.ReadPosition popped = dialogId != 0L
                ? ChatReadPositionStore.pop(context.getApplicationContext(), dialogId)
                : null;
        int targetMessageId = popped != null ? popped.messageId : 0;
        info("ScrollToTop: dialogId=" + dialogId + " targetMsgId=" + targetMessageId + " popped=" + (popped != null));
        if (targetMessageId > 0) {
            Toast.makeText(context, localizedScrollToLastStarted(context), Toast.LENGTH_SHORT).show();
            boolean invoked = invokeScrollToMessageId(chatActivity, targetMessageId);
            if (invoked) {
                info("Called scrollToMessageId(" + targetMessageId + ")");
            } else {
                info("scrollToMessageId failed, falling back to scrollToLastMessage");
                suppressNextSaveBeforeJump = true;
                try {
                    Reflect.invokeIfExists(chatActivity, "scrollToLastMessage",
                            new Class<?>[]{boolean.class, boolean.class}, false, false);
                } finally {
                    suppressNextSaveBeforeJump = false;
                }
            }
            Toast.makeText(context, localizedScrollToLastDone(context), Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(context, localizedScrollToTopStarted(context), Toast.LENGTH_SHORT).show();
            suppressNextSaveBeforeJump = true;
            try {
                Reflect.invokeIfExists(chatActivity, "scrollToLastMessage",
                        new Class<?>[]{boolean.class, boolean.class}, false, false);
            } finally {
                suppressNextSaveBeforeJump = false;
            }
            Toast.makeText(context, localizedScrollToTopDone(context), Toast.LENGTH_SHORT).show();
        }
    }

    private boolean invokeScrollToMessageId(Object chatActivity, int messageId) {
        try {
            Class<?> clazz = chatActivity.getClass();
            Method method = Reflect.method(
                    clazz,
                    "scrollToMessageId",
                    int.class, int.class, boolean.class, int.class, boolean.class, int.class
            );
            Reflect.invoke(method, chatActivity, messageId, 0, true, 0, true, 0);
            return true;
        } catch (NoSuchMethodException ignored) {
            info("scrollToMessageId(IIZIZI) not found");
            return false;
        } catch (Throwable throwable) {
            error("scrollToMessageId invoke failed", throwable);
            return false;
        }
    }

    private int resolveScrollTopIcon(Context context) {
        int telegramIcon = context.getResources().getIdentifier("msg_go_up", "drawable", context.getPackageName());
        if (telegramIcon != 0) return telegramIcon;
        telegramIcon = context.getResources().getIdentifier("msg_arrow_up", "drawable", context.getPackageName());
        if (telegramIcon != 0) return telegramIcon;
        return android.R.drawable.ic_menu_upload;
    }

    private CharSequence localizedScrollTopLabel(Context context) {
        return isChineseLocale(context) ? "跳转到上次浏览" : "Jump to last viewed";
    }

    private CharSequence localizedScrollToTopStarted(Context context) {
        return isChineseLocale(context) ? "正在跳转到频道顶部…" : "Jumping to channel top…";
    }

    private CharSequence localizedScrollToLastStarted(Context context) {
        return isChineseLocale(context) ? "正在跳转到上次浏览的消息…" : "Jumping to last viewed message…";
    }

    private CharSequence localizedScrollToTopDone(Context context) {
        return isChineseLocale(context) ? "已到达频道顶部" : "Reached channel top";
    }

    private CharSequence localizedScrollToLastDone(Context context) {
        return isChineseLocale(context) ? "已到达上次浏览的消息" : "Reached last viewed message";
    }

    private CharSequence localizedScrollToLastNotFound(Context context) {
        return isChineseLocale(context) ? "未找到上次浏览的消息" : "Last viewed message not found";
    }

    private CharSequence localizedScrollGaveUp(Context context) {
        return isChineseLocale(context) ? "跳转失败" : "Could not reach target";
    }

    private CharSequence localizedScrollUnavailable(Context context) {
        return isChineseLocale(context) ? "无法获取消息列表" : "Cannot access message list";
    }

    private void injectGlobalSettingsMenu(Object host, boolean requireSettingsFlag) {
        if (requireSettingsFlag) {
            Object isSettings = Reflect.invokeIfExists(host, "isSettings", new Class<?>[0]);
            if (isSettings instanceof Boolean) {
                if (!((Boolean) isSettings)) {
                    return;
                }
            } else {
                info(host.getClass().getSimpleName() + ".isSettings unavailable; attempting fallback menu injection");
            }
        }
        Object otherItem = resolveOverflowMenuItem(host);
        if (otherItem == null) {
            info(host.getClass().getSimpleName() + " overflow menu item not found");
            return;
        }
        if (hasMenuItem(otherItem, MENU_ID_GLOBAL)) {
            return;
        }
        Context context = contextFromMenuItem(otherItem);
        int iconRes = resolveIcon(context);
        Object subItem = addMenuSubItem(otherItem, MENU_ID_GLOBAL, iconRes, localizedGlobalMenuLabel(context));
        if (!(subItem instanceof View)) {
            info(host.getClass().getSimpleName() + " menu addSubItem unavailable on " + otherItem.getClass().getName());
            return;
        }
        View subItemView = (View) subItem;
        subItemView.setTag(R.id.gramsieve_menu_item_id, MENU_ID_GLOBAL);
        subItemView.setOnClickListener(v -> {
            try {
                openConfigFromHost(host, v.getContext(), CONFIG_MODE_GLOBAL, 0L, "");
            } finally {
                Reflect.invokeIfExists(otherItem, "toggleSubMenu", new Class<?>[0]);
            }
        });
    }

    private void injectSettingsListRow(Object host, ArrayList<Object> items, Method factoryOf) {
        if (items == null || containsGramSieveSettingsItem(items)) {
            return;
        }
        Context context = contextFromSettingsHost(host);
        Object item = Reflect.invoke(
                factoryOf,
                null,
                SETTINGS_ROW_GRAMSIEVE,
                SETTINGS_ROW_COLOR_START,
                SETTINGS_ROW_COLOR_END,
                resolveIcon(context),
                localizedSettingsRowLabel(context),
                localizedSettingsRowSubtitle(context)
        );
        if (item == null) {
            return;
        }
        int index = settingsRowInsertIndex(items);
        items.add(index, item);
        if (!settingsListRowLogged) {
            settingsListRowLogged = true;
            info("Injected GramSieve settings row at index=" + index);
        }
    }

    private boolean containsGramSieveSettingsItem(List<?> items) {
        for (Object item : items) {
            if (isGramSieveSettingsItem(item)) {
                return true;
            }
        }
        return false;
    }

    private boolean isGramSieveSettingsItem(Object item) {
        if (settingsItemId(item) == SETTINGS_ROW_GRAMSIEVE) {
            return true;
        }
        String text = settingsItemText(item, "text");
        return "GramSieve".contentEquals(text);
    }

    private int settingsRowInsertIndex(List<?> items) {
        for (int i = 0; i < items.size(); i++) {
            Object item = items.get(i);
            String text = settingsItemText(item, "text");
            String subtext = settingsItemText(item, "subtext");
            if (!text.isBlank() && !subtext.isBlank()) {
                return i;
            }
        }
        return items.size();
    }

    private int settingsItemId(Object item) {
        if (item == null) {
            return Integer.MIN_VALUE;
        }
        return Reflect.asInt(Reflect.field(item, "id"), Integer.MIN_VALUE);
    }

    private String settingsItemText(Object item, String fieldName) {
        return Reflect.asString(Reflect.field(item, fieldName)).trim();
    }

    private Context contextFromSettingsClick(Object host, Object clickedView) {
        if (clickedView instanceof View) {
            Context context = ((View) clickedView).getContext();
            if (context != null) {
                return context;
            }
        }
        return contextFromSettingsHost(host);
    }

    private Context contextFromSettingsHost(Object host) {
        View view = resolveHostFragmentView(host);
        if (view != null && view.getContext() != null) {
            return view.getContext();
        }
        Object context = Reflect.invokeIfExists(host, "getContext", new Class<?>[0]);
        if (context instanceof Context) {
            return (Context) context;
        }
        Object activity = Reflect.invokeIfExists(host, "getParentActivity", new Class<?>[0]);
        if (activity instanceof Context) {
            return (Context) activity;
        }
        return resolveHostApplication();
    }

    private boolean closeHostConfigPanel(Object host) {
        Context context = contextFromSettingsHost(host);
        ViewGroup root = resolveHostConfigRoot(host, context);
        boolean closed = HostConfigPanel.closeExisting(root);
        if (closed) {
            info("Closed host config panel from SettingsActivity back");
        }
        return closed;
    }

    private Object resolveOverflowMenuItem(Object host) {
        Object direct = Reflect.field(host, "otherItem");
        if (direct != null) {
            return direct;
        }
        Object actionBar = Reflect.field(host, "actionBar");
        if (actionBar == null) {
            return null;
        }
        Object menu = Reflect.field(actionBar, "menu");
        if (menu instanceof ViewGroup) {
            Object lastItem = lastActionBarMenuItem((ViewGroup) menu);
            if (lastItem != null) {
                return lastItem;
            }
        }
        if (actionBar instanceof ViewGroup) {
            return findMenuItemFromActionBar((ViewGroup) actionBar);
        }
        return null;
    }

    private Object findMenuItemFromActionBar(ViewGroup actionBar) {
        for (int i = actionBar.getChildCount() - 1; i >= 0; i--) {
            View child = actionBar.getChildAt(i);
            if (child instanceof ViewGroup) {
                Object lastItem = lastActionBarMenuItem((ViewGroup) child);
                if (lastItem != null) {
                    return lastItem;
                }
            }
        }
        return null;
    }

    private Object lastActionBarMenuItem(ViewGroup group) {
        for (int i = group.getChildCount() - 1; i >= 0; i--) {
            View child = group.getChildAt(i);
            if (child.getClass().getName().contains("ActionBarMenuItem")) {
                return child;
            }
        }
        return null;
    }

    private boolean hasMenuItem(Object menuItem, int targetId) {
        Object popupLayout = Reflect.invokeIfExists(menuItem, "getPopupLayout", new Class<?>[0]);
        if (!(popupLayout instanceof ViewGroup)) {
            return false;
        }
        return hasMenuItemInGroup((ViewGroup) popupLayout, targetId);
    }

    private boolean hasMenuItemInGroup(ViewGroup group, int targetId) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child == null) {
                continue;
            }
            Object keyedTag = child.getTag(R.id.gramsieve_menu_item_id);
            if (keyedTag instanceof Integer && ((Integer) keyedTag) == targetId) {
                return true;
            }
            Object tag = child.getTag();
            if (tag instanceof Integer && ((Integer) tag) == targetId) {
                return true;
            }
            if (child instanceof ViewGroup && hasMenuItemInGroup((ViewGroup) child, targetId)) {
                return true;
            }
        }
        return false;
    }

    private boolean isChatMenuRecorded(Object headerItem, int menuId) {
        synchronized (injectedChatMenuIds) {
            java.util.Set<Integer> ids = injectedChatMenuIds.get(headerItem);
            return ids != null && ids.contains(menuId);
        }
    }

    // Build #31: the duplicate-menu fix. hasMenuItem alone cannot
    // dedup: at createView/onResume time the popup is closed, so
    // getPopupLayout is empty or null and the check always returns
    // false, while addSubItem appends to the header's internal list
    // every time (createView + every onResume since #27). The menu
    // then shows one copy per injection, exactly as in the 23:44
    // screenshot. This helper records each id per headerItem and
    // refuses a second add for the same header, so re-injection is
    // a no-op; an item skipped earlier (chat not loaded yet) is not
    // recorded and is still added on a later resume.
    private Object addChatMenuSubItem(Object chatActivity, Object headerItem, int menuId,
            int iconRes, CharSequence title) {
        try {
            synchronized (injectedChatMenuIds) {
                java.util.Set<Integer> ids = injectedChatMenuIds.get(headerItem);
                if (ids != null && ids.contains(menuId)) {
                    return null;
                }
            }
            if (hasMenuItem(headerItem, menuId)) {
                synchronized (injectedChatMenuIds) {
                    java.util.Set<Integer> ids = injectedChatMenuIds.get(headerItem);
                    if (ids == null) {
                        ids = new java.util.HashSet<>();
                        injectedChatMenuIds.put(headerItem, ids);
                    }
                    ids.add(menuId);
                }
                return null;
            }
            Object subItem = addMenuSubItem(headerItem, menuId, iconRes, title);
            if (subItem instanceof View) {
                synchronized (injectedChatMenuIds) {
                    java.util.Set<Integer> ids = injectedChatMenuIds.get(headerItem);
                    if (ids == null) {
                        ids = new java.util.HashSet<>();
                        injectedChatMenuIds.put(headerItem, ids);
                    }
                    ids.add(menuId);
                }
            }
            return subItem;
        } catch (Throwable ignored) {
            return addMenuSubItem(headerItem, menuId, iconRes, title);
        }
    }

    private Object addMenuSubItem(Object menuItem, int menuId, int iconRes, CharSequence title) {
        Object subItem = Reflect.invokeIfExists(
                menuItem,
                "addSubItem",
                new Class<?>[]{int.class, int.class, CharSequence.class},
                menuId,
                iconRes,
                title
        );
        if (subItem != null) {
            return subItem;
        }
        return Reflect.invokeIfExists(
                menuItem,
                "addSubItem",
                new Class<?>[]{int.class, CharSequence.class},
                menuId,
                title
        );
    }

    private Context contextFromMenuItem(Object menuItem) {
        if (menuItem instanceof View) {
            return ((View) menuItem).getContext();
        }
        Object context = Reflect.invokeIfExists(menuItem, "getContext", new Class<?>[0]);
        if (context instanceof Context) {
            return (Context) context;
        }
        throw new IllegalStateException("Menu item is not a view: " + menuItem);
    }

    private int resolveIcon(Context context) {
        if (context == null) {
            return android.R.drawable.ic_menu_manage;
        }
        int telegramIcon = context.getResources().getIdentifier("msg_settings", "drawable", context.getPackageName());
        return telegramIcon != 0 ? telegramIcon : android.R.drawable.ic_menu_manage;
    }

    private void adjustMenuItemShape(View view, boolean top, boolean bottom) {
        if (view == null) {
            return;
        }
        Reflect.invokeIfExists(view, "updateSelectorBackground", new Class<?>[]{boolean.class, boolean.class}, top, bottom);
    }

    private CharSequence localizedBlockMessageLabel(Context context) {
        return isChineseLocale(context) ? "屏蔽此消息" : "Block this message";
    }

    private CharSequence localizedSmartBlockLabel(Context context) {
        return isChineseLocale(context) ? "智能分析并屏蔽" : "Smart block";
    }

    private CharSequence localizedSmartBlockDialogTitle(Context context) {
        return isChineseLocale(context) ? "智能分析并屏蔽" : "Smart block";
    }

    private CharSequence localizedMarkMessageLabel(Context context) {
        return isChineseLocale(context) ? "标记此消息" : "Mark this message";
    }

    private CharSequence localizedJumpToMarkLabel(Context context) {
        return isChineseLocale(context) ? "跳转到标记位置" : "Jump to marked position";
    }

    private CharSequence localizedMarkSavedToast(Context context) {
        return isChineseLocale(context) ? "已标记此消息" : "Message marked";
    }

    private CharSequence localizedNoMarkToast(Context context) {
        return isChineseLocale(context) ? "没有标记位置" : "No marked position";
    }

    private CharSequence localizedJumpToMarkStarted(Context context) {
        return isChineseLocale(context) ? "正在跳转到标记位置…" : "Jumping to marked position…";
    }

    private CharSequence localizedJumpToMarkDone(Context context) {
        return isChineseLocale(context) ? "已到达标记位置" : "Reached marked position";
    }

    private CharSequence localizedChatMenuLabel(Context context) {
        return isChineseLocale(context) ? "聊天过滤规则" : "Chat filters";
    }

    private CharSequence localizedGlobalMenuLabel(Context context) {
        return isChineseLocale(context) ? "过滤规则" : "Filters";
    }

    private CharSequence localizedSettingsRowLabel(Context context) {
        return "GramSieve";
    }

    private CharSequence localizedSettingsRowSubtitle(Context context) {
        return isChineseLocale(context) ? "过滤、反撤回、编辑历史" : "Filters, anti-recall, edit history";
    }

    private CharSequence localizedSavedToast(Context context) {
        return isChineseLocale(context) ? "已把这条消息加入屏蔽规则" : "Added a rule for this message";
    }

    private CharSequence localizedNoTextToast(Context context) {
        return isChineseLocale(context) ? "这条消息没有可提取的文字" : "This message has no text to extract";
    }

    private boolean isChineseLocale(Context context) {
        // Conso adaptation: force Chinese UI for all menus, toasts and dialogs,
        // regardless of the host app's locale.
        return true;
    }

    private int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    // Build #24 UI audit: every system AlertDialog in this module must
    // go through this after show(), otherwise a dark host gets a white
    // dialog with black text (the complaint from 2026-10-25). It forces
    // the window to the host theme background and recolours title,
    // message and buttons to the host theme text/accent colours.
    // Build #30 unified dialog theme: the repeated white-on-white
    // bug came from two different theme sources. Installer dialogs
    // took text from the Android theme attrs (white in a dark host)
    // while the system AlertDialog kept a white content background,
    // and styleSystemDialog only recoloured the window, not the
    // content, swallowing every failure. From now on every installer
    // dialog resolves background AND text from the SAME source,
    // Telegram's own Theme keys (the same keys HostConfigPanel
    // already uses, so panel and dialogs can no longer disagree),
    // and any text colour that still fails 4.5:1 against its
    // background is flipped to black or white, whichever reads.
    // Resolved colours are logged (hex only) instead of swallowed,
    // so the next log file shows what the dialog actually used.
    private int telegramThemeColor(Context context, String keyFieldName, int fallback) {
        try {
            Class<?> themeClass = Class.forName("org.telegram.ui.ActionBar.Theme", false, context.getClassLoader());
            java.lang.reflect.Field keyField = themeClass.getDeclaredField(keyFieldName);
            keyField.setAccessible(true);
            java.lang.reflect.Method getColor = themeClass.getDeclaredMethod("getColor", int.class);
            getColor.setAccessible(true);
            Object color = getColor.invoke(null, keyField.getInt(null));
            return color instanceof Integer ? (Integer) color : fallback;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private int dialogBackgroundColor(Context context) {
        return telegramThemeColor(context, "key_windowBackgroundWhite",
                panelThemeColor(context, android.R.attr.colorBackground, 0xFF121212));
    }

    private int dialogPrimaryColor(Context context) {
        return telegramThemeColor(context, "key_windowBackgroundWhiteBlackText",
                panelThemeColor(context, android.R.attr.textColorPrimary, 0xFFFFFFFF));
    }

    private int dialogSecondaryColor(Context context) {
        return telegramThemeColor(context, "key_windowBackgroundWhiteGrayText2",
                panelThemeColor(context, android.R.attr.textColorSecondary, 0xFFAAAAAA));
    }

    private int dialogAccentColor(Context context) {
        return telegramThemeColor(context, "key_dialogButton",
                panelThemeColor(context, android.R.attr.colorAccent, 0xFF2AABEE));
    }

    private static float dialogLuminance(int color) {
        float r = android.graphics.Color.red(color) / 255f;
        float g = android.graphics.Color.green(color) / 255f;
        float b = android.graphics.Color.blue(color) / 255f;
        r = r <= 0.03928f ? r / 12.92f : (float) Math.pow((r + 0.055f) / 1.055f, 2.4);
        g = g <= 0.03928f ? g / 12.92f : (float) Math.pow((g + 0.055f) / 1.055f, 2.4);
        b = b <= 0.03928f ? b / 12.92f : (float) Math.pow((b + 0.055f) / 1.055f, 2.4);
        return 0.2126f * r + 0.7152f * g + 0.0722f * b;
    }

    private static int ensureDialogTextColor(int foreground, int background) {
        float light = Math.max(dialogLuminance(foreground), dialogLuminance(background));
        float dark = Math.min(dialogLuminance(foreground), dialogLuminance(background));
        if ((light + 0.05f) / (dark + 0.05f) >= 4.5f) {
            return foreground;
        }
        return dialogLuminance(background) > 0.5f ? 0xFF000000 : 0xFFFFFFFF;
    }

    // Build #32: delegates to DialogTheme, the one system for all
    // dialogs, so this method can no longer drift from the panel's
    // dialogs. The log line stays, so existing log greps keep working.
    private void styleSystemDialog(AlertDialog dialog, Context context) {
        try {
            if (dialog == null) {
                return;
            }
            DialogTheme.Colors colors = DialogTheme.resolve(context);
            info("Dialog theme: bg=#" + String.format("%08X", colors.background)
                    + " primary=#" + String.format("%08X", colors.primary)
                    + " accent=#" + String.format("%08X", colors.accent));
            DialogTheme.styleAlertDialog(dialog, colors);
        } catch (Throwable throwable) {
            error("Dialog theme: styleSystemDialog failed", throwable);
        }
    }

    private int panelThemeColor(Context context, int attr, int fallback) {
        try {
            android.util.TypedValue typedValue = new android.util.TypedValue();
            if (context != null && context.getTheme() != null
                    && context.getTheme().resolveAttribute(attr, typedValue, true)) {
                return typedValue.data;
            }
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    private String resolveChatTitle(Object chatActivity) {
        Object currentChat = Reflect.field(chatActivity, "currentChat");
        String chatTitle = Reflect.asString(Reflect.field(currentChat, "title")).trim();
        if (!chatTitle.isBlank()) {
            return chatTitle;
        }
        Object currentUser = Reflect.field(chatActivity, "currentUser");
        String first = Reflect.asString(Reflect.field(currentUser, "first_name")).trim();
        String last = Reflect.asString(Reflect.field(currentUser, "last_name")).trim();
        String full = (first + " " + last).trim();
        if (!full.isBlank()) {
            return full;
        }
        return Reflect.asString(Reflect.field(currentUser, "username")).trim();
    }

    private void openConfigFromHost(Object host, Context context, String mode, long dialogId, String title) {
        try {
            if (showHostConfigPanel(host, context, mode, dialogId, title)) {
                return;
            }
        } catch (Throwable throwable) {
            error("Host config panel failed", throwable);
        }
        if (context != null) {
            Toast.makeText(
                    context,
                    isChineseLocale(context)
                            ? "无法在 Telegram 内打开 GramSieve 设置"
                            : "Could not open GramSieve settings in Telegram",
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    private boolean showHostConfigPanel(Object host, Context context, String mode, long dialogId, String title) {
        if (context == null || configProvider == null) {
            return false;
        }
        boolean chatMode = CONFIG_MODE_CHAT.equals(mode);
        if (chatMode && backgroundMessageLoader == null) {
            initAntiRecallFromChat(host);
        } else if (!chatMode && antiRecallConfigStore == null) {
            initAntiRecallDeferred();
        }

        ViewGroup root = resolveHostConfigRoot(host, context);
        if (root == null) {
            info("Host config panel root unavailable for " + (host == null ? "null" : host.getClass().getName()));
            return false;
        }

        Context hostContext = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        AntiRecallConfigStore antiStore = antiRecallConfigStore != null
                ? antiRecallConfigStore
                : new AntiRecallConfigStore(hostContext);
        // UI sync (Build #15): bypass the 1500ms config cache throttle when
        // opening Settings, so rules just added (e.g. via smart-block) are
        // visible immediately instead of showing the previous count.
        try { configProvider.invalidate(); } catch (Throwable ignored) {}
        FilterConfig config = configProvider.getConfig(hostContext).deepCopy();
        boolean shown = HostConfigPanel.show(
                context,
                root,
                config,
                chatMode,
                dialogId,
                title,
                antiStore,
                backgroundMessageLoader,
                updated -> {
                    FilterConfig saved = saveUpdatedConfig(hostContext, updated);
                    decisionCache.clear();
                    return saved;
                },
                () -> {
                    decisionCache.clear();
                    if (chatMode) {
                        refreshChatActivityFiltering(host);
                    } else {
                        // UI sync (Build #15): pinned highlight / classify bar
                        // switches must take effect without reopening the list.
                        try { refreshDialogsUi(host); } catch (Throwable ignored) {}
                        try { syncClassifyBarWithConfig(host); } catch (Throwable ignored) {}
                        // Build #26: if the panel was opened from Conso
                        // Settings, host is SettingsActivity, not the chat
                        // list, so also sync to the last DialogsActivity
                        // instance, and run the folder-archive sync now
                        // (it otherwise only runs on list recreation or a
                        // folder change, so enabling it from Settings
                        // looked like it did nothing).
                        try {
                            Object dialogsHost = lastDialogsActivityRef == null ? null : lastDialogsActivityRef.get();
                            if (dialogsHost != null && dialogsHost != host) {
                                try { refreshDialogsUi(dialogsHost); } catch (Throwable ignored) {}
                                try { syncClassifyBarWithConfig(dialogsHost); } catch (Throwable ignored) {}
                            }
                            if (dialogsHost != null) {
                                Object dialogsController = messagesControllerForChat(dialogsHost);
                                if (dialogsController != null) {
                                    syncFolderArchive(dialogsController);
                                }
                            }
                        } catch (Throwable ignored) {}
                    }
                }
        );
        if (shown) {
            info("Opened host config panel mode=" + mode + " dialogId=" + dialogId);
        }
        return shown;
    }

    private ViewGroup resolveHostConfigRoot(Object host, Context context) {
        View anchor = resolveHostFragmentView(host);
        if (anchor != null) {
            View rootView = anchor.getRootView();
            if (rootView instanceof ViewGroup) {
                return (ViewGroup) rootView;
            }
            if (anchor instanceof ViewGroup) {
                return (ViewGroup) anchor;
            }
        }

        Activity activity = activityFromContext(context);
        if (activity != null && activity.getWindow() != null) {
            View decorView = activity.getWindow().getDecorView();
            if (decorView instanceof ViewGroup) {
                return (ViewGroup) decorView;
            }
        }
        return null;
    }

    private View resolveHostFragmentView(Object host) {
        if (host instanceof View) {
            return (View) host;
        }
        Object direct = Reflect.field(host, "fragmentView");
        if (direct instanceof View) {
            return (View) direct;
        }
        Object getter = Reflect.invokeIfExists(host, "getFragmentView", new Class<?>[0]);
        if (getter instanceof View) {
            return (View) getter;
        }
        Object contentView = Reflect.field(host, "contentView");
        if (contentView instanceof View) {
            return (View) contentView;
        }
        Object listView = Reflect.field(host, "listView");
        if (listView instanceof View) {
            return (View) listView;
        }
        return null;
    }

    private Activity activityFromContext(Context context) {
        Context current = context;
        for (int i = 0; i < 8 && current != null; i++) {
            if (current instanceof Activity) {
                return (Activity) current;
            }
            if (!(current instanceof ContextWrapper)) {
                break;
            }
            Context next = ((ContextWrapper) current).getBaseContext();
            if (next == current) {
                break;
            }
            current = next;
        }
        return null;
    }

    private void logTelegramVersion(ClassLoader classLoader, ApplicationInfo applicationInfo) {
        String buildVersion = "";
        try {
            Class<?> buildVarsClass = classLoader.loadClass("org.telegram.messenger.BuildVars");
            Object raw = Reflect.staticField(buildVarsClass, "BUILD_VERSION_STRING");
            buildVersion = Reflect.asString(raw).trim();
        } catch (Throwable ignored) {
            buildVersion = "";
        }
        String suffix = buildVersion.isBlank() ? "" : " build=" + buildVersion;
        info("Target Telegram package=" + applicationInfo.packageName + suffix + " source=" + applicationInfo.sourceDir);
    }


    // ------------------------------------------------------------------
    // Enhancement features (Build #5): ported, one hook at a time, from
    // NoAdsTelegram (sponsored/video ads, forward+save, save stories,
    // prevent message deletion) and TeleVip (download speed, local
    // premium via UserConfig.isPremium). Every hook first checks the
    // current FilterConfig, so toggling a switch in Settings takes
    // effect without reinstalling (config reload is throttled to 1.5s
    // by XposedConfigProvider). Hooks that cannot find their target
    // method in Conso simply log and skip -- they never crash the host.
    // Local premium is deliberately client-side only: it makes
    // UserConfig.isPremium() return true, which unlocks client-side
    // checks (e.g. the pinned-chat limit UI, premium stickers/reactions
    // locally). Server-side premium (bigger uploads, voice-to-text,
    // faster server downloads, etc.) still requires a real subscription
    // and will not work from this switch alone.
    // ------------------------------------------------------------------
    private FilterConfig currentEnhancementConfig() {
        try {
            if (configProvider == null) {
                return FilterConfig.createDefault();
            }
            return configProvider.getConfig(resolveHostApplication());
        } catch (Throwable throwable) {
            return FilterConfig.createDefault();
        }
    }

    private void hookEnhancements(ClassLoader classLoader) {
        hookSponsoredAds(classLoader);
        hookForwardSave(classLoader);
        hookSaveStories(classLoader);
        hookPreventMessageDelete(classLoader);
        hookBoostDownloadSpeed(classLoader);
        hookLocalPremium(classLoader);
        hookAutoTranslate(classLoader);
        hookFolderArchiveSync(classLoader);
        hookDialogListFeatures(classLoader);
    }

    // Dialog list features (Build #14, user-selected low-risk route):
    // 1) Pinned highlight: after DialogCell.onDraw, if the cell is pinned
    //    (DialogCell.getIsPinned) and the switch is on, draw a light
    //    accent tint over the cell plus a solid accent bar at the left
    //    edge. Drawing after the host's own onDraw means the host's
    //    background cannot paint over it; the bar sits at x=0..4dp, left
    //    of the avatar, so it does not cover text or the avatar.
    // 2) Auto-classify bar: after DialogsActivity.createView, insert a
    //    horizontal bar below the folder tabs (FilterTabsView) with
    //    All / Personal / Groups / Channels. Tapping a button saves
    //    classifyFilter to config and refreshes the dialog list; the
    //    DialogCell.setDialog hook then hides non-matching cells by
    //    setting them GONE (recycled cells are re-shown when they match
    //    again, so scrolling does not leave stale hidden rows).
    //    Classification uses DialogObject.isUserDialog / isChatDialog and
    //    MessagesController.getUserOrChat: User -> Personal (bots included),
    //    TL_chat or megagroup channel -> Groups, broadcast channel -> Channels.
    //    Unknown / unloaded peers are never hidden, to avoid losing chats
    //    on a classification miss. When the bar switch is off, the filter
    //    is ignored entirely, even if a filter value was saved before.
    private void hookDialogListFeatures(ClassLoader classLoader) {
        try {
            Class<?> dialogCellClass = classLoader.loadClass("org.telegram.ui.Cells.DialogCell");
            // Pinned highlight: onDraw after-proceed.
            try {
                java.lang.reflect.Method onDraw = Reflect.method(dialogCellClass, "onDraw", android.graphics.Canvas.class);
                hook(onDraw, chain -> {
                    Object result = chain.proceed();
                    try {
                        if (!currentEnhancementConfig().highlightPinnedDialogs) {
                            return result;
                        }
                        Object cell = chain.getThisObject();
                        if (!(cell instanceof View)) {
                            return result;
                        }
                        Boolean pinned = (Boolean) Reflect.invokeIfExists(cell, "getIsPinned", new Class<?>[0]);
                        if (!Boolean.TRUE.equals(pinned)) {
                            return result;
                        }
                        Boolean isFolder = (Boolean) Reflect.invokeIfExists(cell, "isDialogFolder", new Class<?>[0]);
                        if (Boolean.TRUE.equals(isFolder)) {
                            return result;
                        }
                        View view = (View) cell;
                        int w = view.getWidth();
                        int h = view.getHeight();
                        if (w <= 0 || h <= 0) {
                            return result;
                        }
                        Object canvasObj = chain.getArg(0);
                        if (!(canvasObj instanceof android.graphics.Canvas)) {
                            return result;
                        }
                        android.graphics.Canvas canvas = (android.graphics.Canvas) canvasObj;
                        android.graphics.Paint tint = new android.graphics.Paint();
                        tint.setColor(0x1A3390EC);
                        tint.setStyle(android.graphics.Paint.Style.FILL);
                        canvas.drawRect(0, 0, w, h, tint);
                        android.graphics.Paint bar = new android.graphics.Paint();
                        bar.setColor(0xFF3390EC);
                        bar.setStyle(android.graphics.Paint.Style.FILL);
                        canvas.drawRect(0, 0, dp(view.getContext(), 4f), h, bar);
                    } catch (Throwable ignored) {
                    }
                    return result;
                });
            } catch (Throwable t) {
                error("Dialog list: DialogCell.onDraw hook failed", t);
            }
            // Classify filter: setDialog after-proceed, all overloads.
            try {
                for (java.lang.reflect.Method candidate : dialogCellClass.getDeclaredMethods()) {
                    if (!"setDialog".equals(candidate.getName())) {
                        continue;
                    }
                    candidate.setAccessible(true);
                    java.lang.reflect.Method method = candidate;
                    hook(method, chain -> {
                        Object result = chain.proceed();
                        try {
                            FilterConfig cfg = currentEnhancementConfig();
                            Object cell = chain.getThisObject();
                            // Build #25: if the bar is missing entirely
                            // (tabs/list were not ready when createView ran,
                            // so the first install silently returned), retry
                            // installing it from the first bound cell, at
                            // most once per 2s, as long as the switch is on.
                            // This runs before the early return below, so it
                            // also works when the current filter is All (0).
                            try {
                                if (cfg.showClassifyBar && cell instanceof View) {
                                    long nowInstallMs = System.currentTimeMillis();
                                    if (nowInstallMs - lastClassifyBarInstallAttemptMs > 2000L) {
                                        View cellView = (View) cell;
                                        View rootView = cellView.getRootView();
                                        if (rootView instanceof ViewGroup
                                                && findViewWithTag((ViewGroup) rootView, "gramsieve_classify_bar") == null) {
                                            lastClassifyBarInstallAttemptMs = nowInstallMs;
                                            Object dialogsHost = lastDialogsActivityRef == null ? null : lastDialogsActivityRef.get();
                                            installClassifyBar(dialogsHost, rootView);
                                        }
                                    }
                                }
                            } catch (Throwable ignored) {
                            }
                            // Build #40: classify bar cancelled, so no
                            // category filtering may hide chats either.
                            int categoryFilter = 0;
                            if (!(cell instanceof View) || !cfg.showClassifyBar || categoryFilter == 0) {
                                if (cell instanceof View) {
                                    ((View) cell).setVisibility(View.VISIBLE);
                                }
                                return result;
                            }
                            long dialogId = 0L;
                            Object idFromCell = Reflect.invokeIfExists(cell, "getDialogId", new Class<?>[0]);
                            if (idFromCell instanceof Number) {
                                dialogId = ((Number) idFromCell).longValue();
                            }
                            if (dialogId == 0L && chain.getArgs() != null && !chain.getArgs().isEmpty()
                                    && chain.getArgs().get(0) instanceof Number) {
                                dialogId = ((Number) chain.getArgs().get(0)).longValue();
                            }
                            if (dialogId == 0L) {
                                ((View) cell).setVisibility(View.VISIBLE);
                                return result;
                            }
                            int type = classifyDialogType(cell, dialogId);
                            // type: 0=unknown (never hide), 1=personal, 2=group, 3=channel
                            if (type == 0 || type == categoryFilter) {
                                ((View) cell).setVisibility(View.VISIBLE);
                            } else {
                                ((View) cell).setVisibility(View.GONE);
                            }
                            // Build #24: keep the bar's highlighted button
                            // in sync when the user switches folder tabs.
                            // Throttled to once per 300ms so binding a whole
                            // list does not spam layout passes.
                            try {
                                long nowMs = System.currentTimeMillis();
                                if (nowMs - lastClassifyBarSyncMs > 300L) {
                                    lastClassifyBarSyncMs = nowMs;
                                    View cellView = (View) cell;
                                    cellView.post(() -> {
                                        try {
                                            View rootView = cellView.getRootView();
                                            if (rootView instanceof ViewGroup) {
                                                View bar = findViewWithTag((ViewGroup) rootView, "gramsieve_classify_bar");
                                                if (bar instanceof ViewGroup) {
                                                    updateClassifyBarButtons((ViewGroup) bar);
                                                }
                                            }
                                        } catch (Throwable ignored) {
                                        }
                                    });
                                }
                            } catch (Throwable ignored) {
                            }
                        } catch (Throwable ignored) {
                        }
                        return result;
                    });
                }
            } catch (Throwable t) {
                error("Dialog list: DialogCell.setDialog hook failed", t);
            }
        } catch (Throwable t) {
            error("Dialog list: DialogCell hooks failed", t);
        }
        try {
            Class<?> dialogsClass = classLoader.loadClass("org.telegram.ui.DialogsActivity");
            java.lang.reflect.Method createView = Reflect.method(dialogsClass, "createView", Context.class);
            hook(createView, chain -> {
                Object result = chain.proceed();
                try {
                    Object host = chain.getThisObject();
                    lastDialogsActivityRef = new java.lang.ref.WeakReference<>(host);
                    if (result instanceof View) {
                        ((View) result).post(() -> {
                            try {
                                if (currentEnhancementConfig().showClassifyBar) {
                                    installClassifyBar(host, (View) result);
                                }
                            } catch (Throwable ignored) {
                            }
                            // TG proxy auto-refresh (Build #20): only when
                            // auto-add is on and the interval has passed;
                            // TgProxyManager guards against spamming.
                            try {
                                maybeAutoRefreshProxies(chain.getThisObject());
                            } catch (Throwable ignored) {
                            }
                            try {
                                Object dialogsController = messagesControllerForChat(chain.getThisObject());
                                if (dialogsController != null) {
                                    syncFolderArchive(dialogsController);
                                }
                            } catch (Throwable ignored) {
                            }
                        });
                    }
                } catch (Throwable t) {
                    error("Dialog list: createView classify bar failed", t);
                }
                return result;
            });
        } catch (Throwable t) {
            error("Dialog list: DialogsActivity.createView hook failed", t);
        }
        // Build #26: returning to the chat list via back button does NOT
        // recreate its view, so createView never re-runs and a switch
        // toggled in Settings never took effect on the existing list.
        // Re-sync on resume, posted so tabs/list are ready.
        try {
            // Build #26 fix: dialogsClass from the createView block is
            // try-block-local, so it is out of scope here. Reload it.
            Class<?> dialogsClassResume = classLoader.loadClass("org.telegram.ui.DialogsActivity");
            java.lang.reflect.Method onResume = Reflect.method(dialogsClassResume, "onResume");
            hook(onResume, chain -> {
                Object result = chain.proceed();
                try {
                    Object host = chain.getThisObject();
                    lastDialogsActivityRef = new java.lang.ref.WeakReference<>(host);
                    View fragmentView = resolveHostFragmentView(host);
                    if (fragmentView != null) {
                        fragmentView.post(() -> {
                            try {
                                if (currentEnhancementConfig().showClassifyBar) {
                                    syncClassifyBarWithConfig(host);
                                }
                            } catch (Throwable ignored) {
                            }
                            try {
                                Object dialogsController = messagesControllerForChat(host);
                                if (dialogsController != null) {
                                    syncFolderArchive(dialogsController);
                                }
                            } catch (Throwable ignored) {
                            }
                        });
                    }
                } catch (Throwable ignored) {
                }
                return result;
            });
        } catch (Throwable t) {
            error("Dialog list: DialogsActivity.onResume hook failed", t);
        }
    }

    private volatile Map<Long, Set<Integer>> lastFolderMembership = null;

    private void hookFolderArchiveSync(ClassLoader classLoader) {
        try {
            Class<?> mcClass = classLoader.loadClass("org.telegram.messenger.MessagesController");
            String[] methodNames = new String[]{"addDialogToFolder", "updateFilterDialogs", "onFilterUpdate", "addFilter", "removeFilter"};
            for (Method candidate : mcClass.getDeclaredMethods()) {
                boolean match = false;
                for (String name : methodNames) {
                    if (name.equals(candidate.getName())) {
                        match = true;
                        break;
                    }
                }
                if (!match) {
                    continue;
                }
                candidate.setAccessible(true);
                Method method = candidate;
                hook(method, chain -> {
                    Object result = chain.proceed();
                    try {
                        syncFolderArchive(chain.getThisObject());
                    } catch (Throwable ignored) {
                    }
                    return result;
                });
            }
            info("Enhancement: hooked MessagesController folder methods for archive sync");
        } catch (Throwable throwable) {
            error("Enhancement: failed to hook folder archive sync", throwable);
        }
    }

    // Build #36: Conso 2.0.0 stores folder membership in
    // TL_dialogFilter.alwaysShow (minus neverShow), not in dialogs:
    // the 2026-10-26 08:20 log shows the 节点 filter with dialogs=0
    // but alwaysShow=38. Reading dialogs alone made proxy scan and
    // archive sync see zero folder chats forever. Fall back to
    // dialogs only when alwaysShow is absent, for older hosts.
    private List<Long> folderMemberDialogIds(Object filter) {
        List<Long> ids = new ArrayList<>();
        try {
            Object neverShow = Reflect.field(filter, "neverShow");
            java.util.Set<Long> excluded = new java.util.HashSet<>();
            if (neverShow instanceof List) {
                for (Object o : (List<?>) neverShow) {
                    long id = Reflect.asLong(o, 0L);
                    if (id != 0L) {
                        excluded.add(id);
                    }
                }
            }
            Object alwaysShow = Reflect.field(filter, "alwaysShow");
            if (alwaysShow instanceof List) {
                for (Object o : (List<?>) alwaysShow) {
                    long id = Reflect.asLong(o, 0L);
                    if (id != 0L && !excluded.contains(id)) {
                        ids.add(id);
                    }
                }
                return ids;
            }
            Object dialogs = Reflect.field(filter, "dialogs");
            if (dialogs instanceof List) {
                for (Object o : (List<?>) dialogs) {
                    long id = Reflect.asLong(o, 0L);
                    if (id != 0L && !excluded.contains(id)) {
                        ids.add(id);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return ids;
    }

    private void syncFolderArchive(Object controller) {
        if (controller == null || configProvider == null) {
            return;
        }
        try {
            FilterConfig config = currentEnhancementConfig();
            if (!config.autoArchiveFolderChats) {
                lastFolderMembership = null;
                info("FolderArchive: auto-archive off, tracking cleared");
                return;
            }
            Object filtersObj = Reflect.invokeIfExists(controller, "getDialogFilters", new Class<?>[0]);
            if (!(filtersObj instanceof List)) {
                info("FolderArchive: no filter list, sync skipped");
                return;
            }
            // Build #40: when the list is (re)created, filters may
            // briefly be empty; treating that as "everything left its
            // folder" unarchives chats and then re-archives them on the
            // next scan (seen 2026-10-26 09:41: folders=0, unarchive=1,
            // then 141 re-archived). Skip without touching the baseline.
            boolean anyUserFilter = false;
            for (Object filter : (List<?>) filtersObj) {
                String filterName0 = Reflect.asString(Reflect.field(filter, "name"));
                if (filterName0 == null || !"ALL_CHATS".equalsIgnoreCase(filterName0.trim())) {
                    anyUserFilter = true;
                    break;
                }
            }
            if (!anyUserFilter) {
                info("FolderArchive: no user filters yet, sync skipped");
                return;
            }
            Map<Long, Set<Integer>> current = new HashMap<>();
            int userFilterCount = 0;
            for (Object filter : (List<?>) filtersObj) {
                // Build #25: ALL_CHATS is Telegram's built-in "All Chats"
                // pseudo-filter, not a user folder. Treating it as a folder
                // would archive every chat in the app, not just folder
                // chats, and could create a second Archive entry.
                String filterName = Reflect.asString(Reflect.field(filter, "name"));
                if (filterName != null && "ALL_CHATS".equalsIgnoreCase(filterName.trim())) {
                    continue;
                }
                userFilterCount++;
                int filterId = Reflect.asInt(Reflect.field(filter, "id"), -1);
                for (long dialogId : folderMemberDialogIds(filter)) {
                    Set<Integer> set = current.get(dialogId);
                    if (set == null) {
                        set = new HashSet<>();
                        current.put(dialogId, set);
                    }
                    set.add(filterId);
                }
            }
            Set<Long> autoArchived = new HashSet<>();
            if (config.autoArchivedDialogIds != null) {
                autoArchived.addAll(config.autoArchivedDialogIds);
            }
            Set<Long> optOut = new HashSet<>();
            if (config.folderArchiveOptOutDialogIds != null) {
                optOut.addAll(config.folderArchiveOptOutDialogIds);
            }
            Map<Long, Boolean> changes = new HashMap<>();
            Map<Long, Set<Integer>> previous = lastFolderMembership;
            if (previous == null) {
                for (Map.Entry<Long, Set<Integer>> entry : current.entrySet()) {
                    long dialogId = entry.getKey();
                    if (optOut.contains(dialogId)) {
                        continue;
                    }
                    int folderId = currentFolderId(controller, dialogId);
                    if (folderId == 1) {
                        continue;
                    }
                    if (autoArchived.contains(dialogId)) {
                        autoArchived.remove(dialogId);
                        optOut.add(dialogId);
                    } else {
                        changes.put(dialogId, true);
                    }
                }
                for (Long dialogId : new ArrayList<>(autoArchived)) {
                    if (!current.containsKey(dialogId)) {
                        changes.put(dialogId, false);
                    }
                }
                for (Long dialogId : new ArrayList<>(optOut)) {
                    if (!current.containsKey(dialogId)) {
                        optOut.remove(dialogId);
                    }
                }
            } else {
                for (Map.Entry<Long, Set<Integer>> entry : current.entrySet()) {
                    long dialogId = entry.getKey();
                    if (previous.containsKey(dialogId)) {
                        continue;
                    }
                    if (optOut.contains(dialogId)) {
                        continue;
                    }
                    if (currentFolderId(controller, dialogId) != 1 && !autoArchived.contains(dialogId)) {
                        changes.put(dialogId, true);
                    }
                }
                for (Long dialogId : previous.keySet()) {
                    if (current.containsKey(dialogId)) {
                        continue;
                    }
                    if (autoArchived.contains(dialogId)) {
                        changes.put(dialogId, false);
                    }
                    if (optOut.contains(dialogId)) {
                        optOut.remove(dialogId);
                    }
                }
            }
            // Build #31: one scan line per sync, counts only, so the
            // next log file shows whether the sync is stable or keeps
            // re-archiving (the duplicate-archive-rows question) and
            // how many folder chats it sees, without any dialog ids.
            int archiveCount = 0;
            int unarchiveCount = 0;
            for (Boolean value : changes.values()) {
                if (value) {
                    archiveCount++;
                } else {
                    unarchiveCount++;
                }
            }
            info("FolderArchive: scan mode=" + (previous == null ? "first" : "delta")
                    + " folders=" + userFilterCount + " chatsInFolders=" + current.size()
                    + " changes=" + changes.size() + " archive=" + archiveCount + " unarchive=" + unarchiveCount);
            lastFolderMembership = current;
            boolean changed = false;
            if (!changes.isEmpty() && applyArchiveChanges(controller, changes)) {
                for (Map.Entry<Long, Boolean> entry : changes.entrySet()) {
                    if (entry.getValue()) {
                        autoArchived.add(entry.getKey());
                    } else {
                        autoArchived.remove(entry.getKey());
                    }
                }
                changed = true;
            }
            if (!optOut.equals(config.folderArchiveOptOutDialogIds == null
                    ? new HashSet<Long>() : new HashSet<>(config.folderArchiveOptOutDialogIds))) {
                changed = true;
            }
            if (!autoArchived.equals(config.autoArchivedDialogIds == null
                    ? new HashSet<Long>() : new HashSet<>(config.autoArchivedDialogIds))) {
                changed = true;
            }
            if (changed) {
                try {
                    Context appContext = resolveHostApplication();
                    if (appContext != null) {
                        FilterConfig updated = config.deepCopy().sanitize();
                        updated.autoArchivedDialogIds = new ArrayList<>(autoArchived);
                        updated.folderArchiveOptOutDialogIds = new ArrayList<>(optOut);
                        updated.updatedAtEpochMs = System.currentTimeMillis();
                        saveUpdatedConfig(appContext, updated);
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable throwable) {
            error("Folder-archive sync failed", throwable);
        }
    }

    private int currentFolderId(Object controller, long dialogId) {
        try {
            Object dialog = Reflect.invokeIfExists(controller, "getDialog", new Class<?>[]{long.class}, dialogId);
            if (dialog != null) {
                Object folderId = Reflect.field(dialog, "folder_id");
                if (folderId instanceof Number) {
                    return ((Number) folderId).intValue();
                }
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    private boolean applyArchiveChanges(Object controller, Map<Long, Boolean> changes) {
        try {
            int account = savedClassLoader != null ? resolveSelectedTelegramAccount(savedClassLoader) : 0;
            if (account < 0) {
                account = 0;
            }
            ClassLoader cl = savedClassLoader != null ? savedClassLoader : controller.getClass().getClassLoader();
            Class<?> folderPeerClass = cl.loadClass("org.telegram.tgnet.TLRPC$TL_folderPeer");
            Class<?> editClass = cl.loadClass("org.telegram.tgnet.TLRPC$TL_folders_editPeerFolders");
            Object request = editClass.getDeclaredConstructor().newInstance();
            ArrayList<Object> peers = new ArrayList<>();
            for (Map.Entry<Long, Boolean> entry : changes.entrySet()) {
                Object peer = Reflect.invokeIfExists(controller, "getPeer", new Class<?>[]{long.class}, entry.getKey());
                if (peer == null) {
                    continue;
                }
                Object folderPeer = folderPeerClass.getDeclaredConstructor().newInstance();
                Reflect.setField(folderPeer, "folder_id", entry.getValue() ? 1 : 0);
                Reflect.setField(folderPeer, "peer", peer);
                peers.add(folderPeer);
            }
            if (peers.isEmpty()) {
                return false;
            }
            Reflect.setField(request, "folder_peers", peers);
            Class<?> cmClass = cl.loadClass("org.telegram.tgnet.ConnectionsManager");
            Object cm = Reflect.invokeStatic(cmClass, "getInstance", new Class<?>[]{int.class}, account);
            if (cm == null) {
                return false;
            }
            Class<?> delegateClass = cl.loadClass("org.telegram.tgnet.RequestDelegate");
            final int finalAccount = account;
            final Map<Long, Boolean> finalChanges = new HashMap<>(changes);
            final Object finalController = controller;
            final ClassLoader finalCl = cl;
            Object delegate = java.lang.reflect.Proxy.newProxyInstance(cl, new Class<?>[]{delegateClass},
                    (java.lang.reflect.InvocationHandler) (proxy, method, args) -> {
                        if ("run".equals(method.getName())) {
                            try {
                                Object error = args != null && args.length > 1 ? args[1] : null;
                                if (error != null) {
                                    info("FolderArchive: server rejected archive request");
                                }
                                if (error == null) {
                                    info("FolderArchive: server applied archive changes=" + finalChanges.size());
                                    for (Map.Entry<Long, Boolean> entry : finalChanges.entrySet()) {
                                        try {
                                            Object dialog = Reflect.invokeIfExists(finalController, "getDialog",
                                                    new Class<?>[]{long.class}, entry.getKey());
                                            if (dialog != null) {
                                                Reflect.setField(dialog, "folder_id", entry.getValue() ? 1 : 0);
                                            }
                                        } catch (Throwable ignored) {
                                        }
                                    }
                                    try {
                                        Class<?> ncClass = finalCl.loadClass("org.telegram.messenger.NotificationCenter");
                                        Object nc = Reflect.invokeStatic(ncClass, "getInstance", new Class<?>[]{int.class}, finalAccount);
                                        Object reloadId = Reflect.staticField(ncClass, "dialogsNeedReload");
                                        if (nc != null && reloadId instanceof Number) {
                                            Reflect.invokeIfExists(nc, "postNotificationName",
                                                    new Class<?>[]{int.class, Object[].class},
                                                    ((Number) reloadId).intValue(), new Object[0]);
                                        }
                                    } catch (Throwable ignored) {
                                    }
                                }
                            } catch (Throwable ignored) {
                            }
                            return null;
                        }
                        if ("toString".equals(method.getName())) {
                            return "FolderArchiveDelegate";
                        }
                        if ("hashCode".equals(method.getName())) {
                            return System.identityHashCode(proxy);
                        }
                        if ("equals".equals(method.getName())) {
                            return proxy == (args != null && args.length > 0 ? args[0] : null);
                        }
                        return null;
                    });
            Object reqId = Reflect.invokeIfExists(cm, "sendRequest",
                    new Class<?>[]{cl.loadClass("org.telegram.tgnet.TLObject"), delegateClass}, request, delegate);
            return reqId != null;
        } catch (Throwable throwable) {
            error("Folder-archive apply failed", throwable);
            return false;
        }
    }

    private volatile boolean proxyLoopStarted = false;

    // Build #42: the auto-maintenance loop. List-open refresh alone
    // only fires when the user opens the chat list; while Conso
    // stays alive (even in the background) this hourly tick checks
    // whether the user's refresh interval has passed and runs the
    // same refresh, so scanning, testing, fastest switching,
    // client sync and dead pruning all happen on schedule without
    // the user tapping anything. If Conso is fully killed the loop
    // dies with the process, and the next list open catches up.
    private void startProxyLoop(final Context appContext) {
        if (proxyLoopStarted || configProvider == null) {
            return;
        }
        proxyLoopStarted = true;
        try {
            java.util.concurrent.ScheduledExecutorService loop =
                    java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                        Thread thread = new Thread(r, "GramSieve-ProxyLoop");
                        thread.setDaemon(true);
                        return thread;
                    });
            loop.scheduleAtFixedRate(() -> {
                try {
                    FilterConfig config = configProvider.getConfig(appContext);
                    TgProxyManager.maybeAutoRefresh(appContext, config, result -> {
                        try {
                            if (result != null && result.updated != null) {
                                saveUpdatedConfig(appContext, result.updated);
                            }
                        } catch (Throwable ignored) {
                        }
                    });
                } catch (Throwable ignored) {
                }
            }, 60L, 60L, java.util.concurrent.TimeUnit.MINUTES);
        } catch (Throwable ignored) {
            proxyLoopStarted = false;
        }
    }

    private void maybeAutoRefreshProxies(Object host) {
        try {
            if (host == null || configProvider == null) {
                return;
            }
            Context hostContext = null;
            try {
                if (host instanceof View) {
                    hostContext = ((View) host).getContext();
                }
            } catch (Throwable ignored) {
            }
            if (hostContext == null) {
                hostContext = resolveHostApplication();
            }
            if (hostContext == null) {
                return;
            }
            final Context appContext = hostContext.getApplicationContext() != null
                    ? hostContext.getApplicationContext() : hostContext;
            startProxyLoop(appContext);
            FilterConfig config = configProvider.getConfig(appContext);
            TgProxyManager.maybeAutoRefresh(appContext, config, result -> {
                try {
                    if (result != null && result.updated != null) {
                        saveUpdatedConfig(appContext, result.updated);
                    }
                } catch (Throwable ignored) {
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private int classifyDialogType(Object cell, long dialogId) {
        try {
            ClassLoader cl = cell.getClass().getClassLoader();
            Class<?> dialogObjectClass = cl.loadClass("org.telegram.messenger.DialogObject");
            Boolean isUser = (Boolean) Reflect.invokeStatic(dialogObjectClass, "isUserDialog",
                    new Class<?>[]{long.class}, dialogId);
            if (Boolean.TRUE.equals(isUser)) {
                return 1;
            }
            int account = 0;
            try {
                Object acc = Reflect.field(cell, "currentAccount");
                if (acc instanceof Number) {
                    account = ((Number) acc).intValue();
                }
            } catch (Throwable ignored) {
            }
            Class<?> messagesControllerClass = cl.loadClass("org.telegram.messenger.MessagesController");
            Object controller = Reflect.invokeStatic(messagesControllerClass, "getInstance",
                    new Class<?>[]{int.class}, account);
            if (controller == null) {
                return 0;
            }
            Object peer = Reflect.invokeIfExists(controller, "getUserOrChat",
                    new Class<?>[]{long.class}, dialogId);
            if (peer == null) {
                return 0;
            }
            String peerClass = peer.getClass().getName();
            if (peerClass.contains("TL_user")) {
                return 1;
            }
            if (peerClass.contains("TL_channel")) {
                Object megagroup = Reflect.field(peer, "megagroup");
                return Boolean.TRUE.equals(megagroup) ? 2 : 3;
            }
            if (peerClass.contains("TL_chat")) {
                return 2;
            }
            // Fallback: chat dialogs that are not users are groups.
            Boolean isChat = (Boolean) Reflect.invokeStatic(dialogObjectClass, "isChatDialog",
                    new Class<?>[]{long.class}, dialogId);
            return Boolean.TRUE.equals(isChat) ? 2 : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private void syncClassifyBarWithConfig(Object host) {
        try {
            View rootView = resolveHostFragmentView(host);
            // Build #26: the global panel can be opened from Conso
            // Settings, whose host is NOT DialogsActivity, so resolving
            // the host's fragment view fails and the bar is never
            // installed on the real chat list. Fall back to the last
            // DialogsActivity instance in that case.
            if (!(rootView instanceof ViewGroup)) {
                Object dialogsHost = lastDialogsActivityRef == null ? null : lastDialogsActivityRef.get();
                if (dialogsHost != null && dialogsHost != host) {
                    rootView = resolveHostFragmentView(dialogsHost);
                }
            }
            if (!(rootView instanceof ViewGroup)) {
                // Build #30: log instead of silently returning, so the
                // next exported log file shows exactly why the bar is
                // missing (no root view is the prime suspect after
                // #26/#27/#28 all ran without a bar appearing).
                info("ClassifyBar: no root view, host=" + (host == null ? "null" : host.getClass().getSimpleName())
                        + " show=" + currentEnhancementConfig().showClassifyBar);
                return;
            }
            ViewGroup root = (ViewGroup) rootView;
            View existing = findViewWithTag(root, "gramsieve_classify_bar");
            // Build #40: classify bar cancelled — remove any bar left
            // from an earlier session and never install a new one.
            if (existing != null && existing.getParent() instanceof ViewGroup) {
                ((ViewGroup) existing.getParent()).removeView(existing);
            }
            info("ClassifyBar: cancelled in Build #40, removedExisting=" + (existing != null));
            return;
        } catch (Throwable throwable) {
            error("ClassifyBar: sync failed", throwable);
        }
    }

    private View findViewWithTag(ViewGroup group, String tag) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child != null && tag.equals(child.getTag())) {
                return child;
            }
            if (child instanceof ViewGroup) {
                View found = findViewWithTag((ViewGroup) child, tag);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private void installClassifyBar(Object host, View root) {
        // Build #40: the classify bar (All / Personal / Groups /
        // Channels below the folder tabs) is cancelled per user
        // request — it never installed reliably. The guard below is
        // deliberately a runtime check, not a constant, so the
        // retained body still compiles; it never runs.
        if (root != null || root == null) {
            info("ClassifyBar: cancelled in Build #40, install skipped");
            return;
        }
        try {
            if (!(root instanceof ViewGroup)) {
                info("ClassifyBar: install aborted, root not a ViewGroup");
                return;
            }
            View tabsView = findFilterTabsView((ViewGroup) root);
            ViewGroup parent;
            int insertIndex;
            if (tabsView != null && tabsView.getParent() instanceof ViewGroup) {
                parent = (ViewGroup) tabsView.getParent();
                insertIndex = parent.indexOfChild(tabsView) + 1;
                info("ClassifyBar: tabs found, parent=" + parent.getClass().getSimpleName());
            } else {
                // Fallback: insert above the first dialog list view found.
                View listView = findFirstRecyclerListView((ViewGroup) root);
                if (listView == null || !(listView.getParent() instanceof ViewGroup)) {
                    info("ClassifyBar: install aborted, tabs=null and no list view found, rootTree="
                            + describeViewTree((ViewGroup) root, 0));
                    return;
                }
                parent = (ViewGroup) listView.getParent();
                insertIndex = parent.indexOfChild(listView);
                info("ClassifyBar: tabs not found, falling back to list view, parent=" + parent.getClass().getSimpleName());
            }
            // Idempotent: do not install twice in the same parent.
            for (int i = 0; i < parent.getChildCount(); i++) {
                View child = parent.getChildAt(i);
                if (child != null && "gramsieve_classify_bar".equals(child.getTag())) {
                    updateClassifyBarButtons((ViewGroup) child);
                    return;
                }
            }
            Context context = root.getContext();
            LinearLayout bar = new LinearLayout(context);
            bar.setTag("gramsieve_classify_bar");
            bar.setOrientation(LinearLayout.HORIZONTAL);
            bar.setGravity(android.view.Gravity.CENTER_VERTICAL);
            bar.setPadding(dp(context, 12f), dp(context, 8f), dp(context, 12f), dp(context, 8f));
            String[] labels = new String[]{"全部", "个人", "群聊", "频道"};
            for (int i = 0; i < labels.length; i++) {
                final int filter = i;
                TextView button = new TextView(context);
                button.setText(labels[i]);
                button.setTextSize(14f);
                button.setGravity(android.view.Gravity.CENTER);
                button.setPadding(dp(context, 12f), dp(context, 6f), dp(context, 12f), dp(context, 6f));
                button.setTag(filter);
                button.setOnClickListener(v -> {
                    try {
                        FilterConfig updated = currentEnhancementConfig().deepCopy();
                        String category = currentClassifyCategory();
                        if (updated.classifyFiltersByCategory == null) {
                            updated.classifyFiltersByCategory = new java.util.LinkedHashMap<>();
                        }
                        updated.classifyFiltersByCategory.put(category, filter);
                        updated.classifyFilter = filter;
                        updated.updatedAtEpochMs = System.currentTimeMillis();
                        saveUpdatedConfig(context, updated);
                        updateClassifyBarButtons(bar);
                        Object dialogsActivity = lastDialogsActivityRef == null ? null : lastDialogsActivityRef.get();
                        if (dialogsActivity != null) {
                            refreshDialogsUi(dialogsActivity);
                        } else {
                            bar.requestLayout();
                            bar.invalidate();
                        }
                    } catch (Throwable ignored) {
                    }
                });
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                params.leftMargin = dp(context, 4f);
                params.rightMargin = dp(context, 4f);
                bar.addView(button, params);
            }
            updateClassifyBarButtons(bar);
            if (insertIndex < 0 || insertIndex > parent.getChildCount()) {
                parent.addView(bar, new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            } else {
                parent.addView(bar, insertIndex, new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            info("ClassifyBar: installed, parent=" + parent.getClass().getSimpleName() + " index=" + insertIndex);
        } catch (Throwable t) {
            error("Dialog list: install classify bar failed", t);
        }
    }

    // Classify per category (Build #23): the bar must work under All,
    // Archive and each user folder separately. Category is the folder
    // name as the user sees it ("所有" for the main list, "归档" for
    // Archive, or a folder name); the selected tab is detected from the
    // FilterTabsView by its outlier text colour, falling back to the
    // global filter when detection is impossible, so a detection miss
    // never breaks filtering, it only falls back to the old behaviour.
    private String currentClassifyCategory() {
        try {
            Object host = lastDialogsActivityRef == null ? null : lastDialogsActivityRef.get();
            if (host != null) {
                Object folderId = Reflect.field(host, "folderId");
                if (folderId instanceof Number && ((Number) folderId).intValue() == 1) {
                    return "归档";
                }
                // Build #24: prefer ViewPager page index over tab colour.
                // Colour detection (Build #23) failed on hosts whose tabs
                // do not differentiate selection by text colour, leaving
                // every category mapped to "所有" (i.e. a global filter).
                // Page 0 is All, page i+1 is the i-th user folder in
                // getDialogFilters order (ALL_CHATS excluded, as it is not
                // a tab). If no ViewPager is found, fall back to colour,
                // then to "所有".
                String byPage = classifyCategoryByViewPager(host);
                if (byPage != null && !byPage.isBlank()) {
                    return byPage.trim();
                }
                View rootView = resolveHostFragmentView(host);
                if (rootView instanceof ViewGroup) {
                    View tabsView = findFilterTabsView((ViewGroup) rootView);
                    if (tabsView instanceof ViewGroup) {
                        String selected = selectedFilterTabText((ViewGroup) tabsView);
                        if (selected != null && !selected.isBlank()) {
                            return selected.trim();
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return "所有";
    }

    private View findViewPager(ViewGroup group) {
        if (group == null) {
            return null;
        }
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child != null && child.getClass().getName().contains("ViewPager")) {
                try {
                    java.lang.reflect.Method getCurrentItem =
                            child.getClass().getMethod("getCurrentItem");
                    if (getCurrentItem != null && getCurrentItem.getParameterCount() == 0) {
                        return child;
                    }
                } catch (Throwable ignored) {
                }
            }
            if (child instanceof ViewGroup) {
                View found = findViewPager((ViewGroup) child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private String classifyCategoryByViewPager(Object host) {
        try {
            View rootView = resolveHostFragmentView(host);
            if (!(rootView instanceof ViewGroup)) {
                return null;
            }
            View pager = findViewPager((ViewGroup) rootView);
            if (pager == null) {
                return null;
            }
            Object currentItem = Reflect.invokeIfExists(pager, "getCurrentItem", new Class<?>[0]);
            if (!(currentItem instanceof Number)) {
                return null;
            }
            int pageIndex = ((Number) currentItem).intValue();
            if (pageIndex <= 0) {
                return "所有";
            }
            Object controller = messagesControllerForChat(host);
            if (controller == null) {
                return null;
            }
            Object filtersObj = Reflect.invokeIfExists(controller, "getDialogFilters", new Class<?>[0]);
            if (!(filtersObj instanceof List)) {
                return null;
            }
            java.util.List<String> folderNames = new java.util.ArrayList<>();
            for (Object filter : (List<?>) filtersObj) {
                String name = Reflect.asString(Reflect.field(filter, "name"));
                if (name == null || name.isBlank() || "ALL_CHATS".equalsIgnoreCase(name.trim())) {
                    continue;
                }
                folderNames.add(name.trim());
            }
            int folderIndex = pageIndex - 1;
            if (folderIndex >= 0 && folderIndex < folderNames.size()) {
                return folderNames.get(folderIndex);
            }
            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private String selectedFilterTabText(ViewGroup tabsView) {
        try {
            java.util.List<String> texts = new java.util.ArrayList<>();
            java.util.List<Integer> colors = new java.util.ArrayList<>();
            for (int i = 0; i < tabsView.getChildCount(); i++) {
                View child = tabsView.getChildAt(i);
                TextView textView = null;
                if (child instanceof TextView) {
                    textView = (TextView) child;
                } else if (child instanceof ViewGroup) {
                    textView = findFirstTextView((ViewGroup) child);
                }
                if (textView != null && textView.getText() != null && !textView.getText().toString().isBlank()) {
                    texts.add(textView.getText().toString());
                    colors.add(textView.getCurrentTextColor());
                }
            }
            if (texts.size() < 2 || colors.size() != texts.size()) {
                return null;
            }
            java.util.Map<Integer, Integer> counts = new java.util.HashMap<>();
            for (int color : colors) {
                counts.put(color, counts.getOrDefault(color, 0) + 1);
            }
            int majorityColor = colors.get(0);
            int majorityCount = 0;
            for (java.util.Map.Entry<Integer, Integer> entry : counts.entrySet()) {
                if (entry.getValue() > majorityCount) {
                    majorityCount = entry.getValue();
                    majorityColor = entry.getKey();
                }
            }
            if (counts.size() <= 1) {
                return null;
            }
            String outlier = null;
            int outlierCount = 0;
            for (int i = 0; i < texts.size(); i++) {
                if (colors.get(i) != majorityColor) {
                    outlier = texts.get(i);
                    outlierCount++;
                }
            }
            return outlierCount == 1 ? outlier : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private TextView findFirstTextView(ViewGroup group) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof TextView) {
                return (TextView) child;
            }
            if (child instanceof ViewGroup) {
                TextView found = findFirstTextView((ViewGroup) child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private int classifyFilterForCategory(String category, FilterConfig cfg) {
        if (cfg != null && cfg.classifyFiltersByCategory != null && category != null) {
            Integer value = cfg.classifyFiltersByCategory.get(category);
            if (value != null) {
                if (value < 0) return 0;
                if (value > 3) return 3;
                return value;
            }
        }
        return cfg == null ? 0 : cfg.classifyFilter;
    }

    private void updateClassifyBarButtons(ViewGroup bar) {
        try {
            int selected = classifyFilterForCategory(currentClassifyCategory(), currentEnhancementConfig());
            for (int i = 0; i < bar.getChildCount(); i++) {
                View child = bar.getChildAt(i);
                if (!(child instanceof TextView)) {
                    continue;
                }
                Object tag = child.getTag();
                boolean isSelected = tag instanceof Number && ((Number) tag).intValue() == selected;
                TextView button = (TextView) child;
                if (isSelected) {
                    button.setTextColor(0xFFFFFFFF);
                    android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
                    bg.setColor(0xFF3390EC);
                    bg.setCornerRadius(dp(button.getContext(), 16f));
                    button.setBackground(bg);
                } else {
                    button.setTextColor(0xFF8A8A8A);
                    button.setBackgroundColor(android.graphics.Color.TRANSPARENT);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean isNamedView(View view, String simpleName) {
        if (view == null) {
            return false;
        }
        for (Class<?> k = view.getClass(); k != null; k = k.getSuperclass()) {
            if (simpleName.equals(k.getSimpleName())) {
                return true;
            }
        }
        return false;
    }

    // Build #36: compact hierarchy snapshot for the abort log:
    // simple class names only, depth 2, capped, no user data.
    private String describeViewTree(ViewGroup group, int depth) {
        StringBuilder sb = new StringBuilder();
        try {
            sb.append(group.getClass().getSimpleName()).append("[");
            int count = Math.min(group.getChildCount(), 20);
            for (int i = 0; i < count; i++) {
                View child = group.getChildAt(i);
                if (child == null) {
                    continue;
                }
                if (i > 0) {
                    sb.append(",");
                }
                sb.append(child.getClass().getSimpleName());
                if (child instanceof ViewGroup && depth < 2) {
                    sb.append("(").append(((ViewGroup) child).getChildCount()).append(")");
                }
            }
            if (group.getChildCount() > 20) {
                sb.append(",+").append(group.getChildCount() - 20).append(" more");
            }
            sb.append("]");
        } catch (Throwable ignored) {
        }
        return sb.toString();
    }

    private View findFilterTabsView(ViewGroup group) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (isNamedView(child, "FilterTabsView")) {
                return child;
            }
            if (child instanceof ViewGroup) {
                View found = findFilterTabsView((ViewGroup) child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private View findFirstRecyclerListView(ViewGroup group) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (isNamedView(child, "RecyclerListView")) {
                return child;
            }
            if (child instanceof ViewGroup) {
                View found = findFirstRecyclerListView((ViewGroup) child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private void hookBooleanMethod(ClassLoader classLoader, String className, String methodName, java.util.function.Supplier<Boolean> enabled, Object forcedResult) {
        try {
            Class<?> clazz = classLoader.loadClass(className);
            for (Method candidate : clazz.getDeclaredMethods()) {
                if (!methodName.equals(candidate.getName())) {
                    continue;
                }
                candidate.setAccessible(true);
                Method method = candidate;
                hook(method, chain -> {
                    try {
                        if (Boolean.TRUE.equals(enabled.get())) {
                            return forcedResult;
                        }
                    } catch (Throwable ignored) {
                    }
                    return chain.proceed();
                });
            }
            info("Enhancement: hooked " + className + "." + methodName);
        } catch (Throwable throwable) {
            error("Enhancement: failed to hook " + className + "." + methodName, throwable);
        }
    }

    private void hookSponsoredAds(ClassLoader classLoader) {
        // ChatActivity.addSponsoredMessages: void, return null without proceeding.
        try {
            Class<?> clazz = classLoader.loadClass("org.telegram.ui.ChatActivity");
            for (Method candidate : clazz.getDeclaredMethods()) {
                if (!"addSponsoredMessages".equals(candidate.getName())) {
                    continue;
                }
                candidate.setAccessible(true);
                Method method = candidate;
                hook(method, chain -> {
                    if (currentEnhancementConfig().disableSponsoredMessages) {
                        return null;
                    }
                    return chain.proceed();
                });
            }
            info("Enhancement: hooked ChatActivity.addSponsoredMessages");
        } catch (Throwable throwable) {
            error("Enhancement: failed to hook addSponsoredMessages", throwable);
        }
        // Conso's sponsored gates, verified in Conso 2.0.0 dex (2026-10-25
        // fix for Build #7 feedback "ads not recognised"):
        // - MessagesController.getSponsoredMessages(long) returns
        //   SponsoredMessagesInfo, NOT a list. Returning an ArrayList here
        //   was a type mismatch; return null instead, which is the
        //   caller's "no sponsored messages" path.
        // - MessagesController.getSponsoredMessagesCount does NOT exist;
        //   the count lives on ChatActivity.getSponsoredMessagesCount().
        // - isSponsoredDisabled was previously gated on disableVideoAds
        //   by mistake; it must follow disableSponsoredMessages.
        // - MessageObject.isSponsored() marks sponsored message cells.
        hookBooleanMethod(classLoader, "org.telegram.ui.ChatActivity", "getSponsoredMessagesCount",
                () -> currentEnhancementConfig().disableSponsoredMessages, 0);
        try {
            Class<?> clazz = classLoader.loadClass("org.telegram.messenger.MessagesController");
            for (Method candidate : clazz.getDeclaredMethods()) {
                if (!"getSponsoredMessages".equals(candidate.getName())) {
                    continue;
                }
                candidate.setAccessible(true);
                Method method = candidate;
                hook(method, chain -> {
                    if (currentEnhancementConfig().disableSponsoredMessages) {
                        return null;
                    }
                    return chain.proceed();
                });
            }
            hookBooleanMethod(classLoader, "org.telegram.messenger.MessagesController", "isSponsoredDisabled",
                    () -> currentEnhancementConfig().disableSponsoredMessages, true);
        } catch (Throwable throwable) {
            error("Enhancement: failed to hook sponsored messages", throwable);
        }
        hookBooleanMethod(classLoader, "org.telegram.messenger.MessageObject", "isSponsored",
                () -> currentEnhancementConfig().disableSponsoredMessages, false);
        // VideoAds.load: void, skip loading video ads entirely.
        try {
            Class<?> clazz = classLoader.loadClass("org.telegram.messenger.video.VideoAds");
            for (Method candidate : clazz.getDeclaredMethods()) {
                if (!"load".equals(candidate.getName())) {
                    continue;
                }
                candidate.setAccessible(true);
                Method method = candidate;
                hook(method, chain -> {
                    if (currentEnhancementConfig().disableVideoAds) {
                        return null;
                    }
                    return chain.proceed();
                });
            }
            info("Enhancement: hooked VideoAds.load");
        } catch (Throwable throwable) {
            error("Enhancement: failed to hook VideoAds.load", throwable);
        }
    }

    private void hookForwardSave(ClassLoader classLoader) {
        hookBooleanMethod(classLoader, "org.telegram.messenger.MessagesController", "isChatNoForwards",
                () -> currentEnhancementConfig().enableForwardSave, false);
        hookBooleanMethod(classLoader, "org.telegram.ui.ChatActivity", "hasSelectedNoforwardsMessage",
                () -> currentEnhancementConfig().enableForwardSave, false);
        hookBooleanMethod(classLoader, "org.telegram.messenger.MessageObject", "canForwardMessage",
                () -> currentEnhancementConfig().enableForwardSave, true);
    }

    private void hookSaveStories(ClassLoader classLoader) {
        // Simplified port of NoAds EnableSaveStories: allow screenshots on
        // story holders, and enable any already-present save/gallery menu
        // item in the story viewer menu. This does NOT add a brand-new
        // save item if Conso's story menu has none, and does NOT fake
        // premium for stories (that is the separate local-premium switch).
        hookBooleanMethod(classLoader, "org.telegram.ui.Stories.PeerStoriesView$StoryItemHolder", "allowScreenshots",
                () -> currentEnhancementConfig().enableSaveStories, true);
        try {
            Class<?> clazz = classLoader.loadClass("org.telegram.ui.Stories.PeerStoriesView");
            for (String name : new String[]{"createMenu", "fillMenu", "prepareMenu"}) {
                for (Method candidate : clazz.getDeclaredMethods()) {
                    if (!name.equals(candidate.getName())) {
                        continue;
                    }
                    candidate.setAccessible(true);
                    Method method = candidate;
                    hook(method, chain -> {
                        Object result = chain.proceed();
                        try {
                            if (currentEnhancementConfig().enableSaveStories && result instanceof Menu) {
                                enableSaveMenuItems((Menu) result);
                            }
                        } catch (Throwable ignored) {
                        }
                        return result;
                    });
                }
            }
            info("Enhancement: hooked PeerStoriesView menus for save");
        } catch (Throwable throwable) {
            error("Enhancement: failed to hook PeerStoriesView menus", throwable);
        }
    }

    private void enableSaveMenuItems(Menu menu) {
        for (int i = 0; i < menu.size(); i++) {
            MenuItem item = menu.getItem(i);
            CharSequence title = item.getTitle();
            if (title != null) {
                String lower = title.toString().toLowerCase(Locale.ROOT);
                if (lower.contains("save") || lower.contains("gallery")
                        || lower.contains("保存") || lower.contains("相册")) {
                    item.setEnabled(true);
                }
            }
            SubMenu sub = item.getSubMenu();
            if (sub != null) {
                enableSaveMenuItems(sub);
            }
        }
    }

    private void hookPreventMessageDelete(ClassLoader classLoader) {
        // Port of NoAds DisableDelete: MessagesController has a method
        // (ArrayList, ArrayList, ArrayList, boolean, int) that processes
        // server updates. Filter TL_updateDeleteMessages /
        // TL_updateDeleteChannelMessages out of arg 0 before the original
        // runs. libxposed API 101 has no invokeOrigin-style call with
        // replaced arguments (Build #5 failed to compile on exactly that,
        // 2026-10-25), so instead mutate the incoming ArrayList in place
        // and then chain.proceed(): the original method receives the
        // filtered list through the same reference. If the list becomes
        // empty, skip the original call entirely. This is a second
        // mechanism on top of GramSieve's proactive-loading anti-recall
        // (which caches messages before deletion); both can be on, but
        // this one keeps the message in the UI as well.
        try {
            Class<?> clazz = classLoader.loadClass("org.telegram.messenger.MessagesController");
            for (Method candidate : clazz.getDeclaredMethods()) {
                Class<?>[] params = candidate.getParameterTypes();
                if (params.length != 5 || params[0] != ArrayList.class || params[1] != ArrayList.class
                        || params[2] != ArrayList.class || params[3] != boolean.class || params[4] != int.class) {
                    continue;
                }
                candidate.setAccessible(true);
                Method method = candidate;
                hook(method, chain -> {
                    if (!currentEnhancementConfig().preventMessageDelete) {
                        return chain.proceed();
                    }
                    Object arg0 = chain.getArg(0);
                    if (!(arg0 instanceof ArrayList<?>)) {
                        return chain.proceed();
                    }
                    @SuppressWarnings("unchecked")
                    ArrayList<Object> updates = (ArrayList<Object>) arg0;
                    int before = updates.size();
                    try {
                        updates.removeIf(update -> {
                            if (update == null) {
                                return false;
                            }
                            String name = update.getClass().getName();
                            return "org.telegram.tgnet.TLRPC$TL_updateDeleteMessages".equals(name)
                                    || "org.telegram.tgnet.TLRPC$TL_updateDeleteChannelMessages".equals(name);
                        });
                    } catch (Throwable ignored) {
                        // Unmodifiable list: leave the original call untouched.
                        return chain.proceed();
                    }
                    if (updates.size() == before) {
                        return chain.proceed();
                    }
                    if (updates.isEmpty()) {
                        return null;
                    }
                    return chain.proceed();
                });
                info("Enhancement: hooked MessagesController update-array for delete prevention");
            }
        } catch (Throwable throwable) {
            error("Enhancement: failed to hook delete prevention", throwable);
        }
    }

    private void hookBoostDownloadSpeed(ClassLoader classLoader) {
        // Port of TeleVip DownloadSpeed: after FileLoadOperation.updateParams,
        // raise the big-file chunk size to 512 KiB, parallel requests to 8,
        // and CDN parts to cover a 2000 MB file. This only changes the
        // client's download parallelism; server-side speed limits still apply.
        try {
            Class<?> clazz = classLoader.loadClass("org.telegram.messenger.FileLoadOperation");
            for (Method candidate : clazz.getDeclaredMethods()) {
                if (!"updateParams".equals(candidate.getName())) {
                    continue;
                }
                candidate.setAccessible(true);
                Method method = candidate;
                hook(method, chain -> {
                    Object result = chain.proceed();
                    try {
                        if (currentEnhancementConfig().boostDownloadSpeed) {
                            Object op = chain.getThisObject();
                            Reflect.setField(op, "downloadChunkSizeBig", 1024 * 512);
                            Reflect.setField(op, "maxDownloadRequests", 8);
                            Reflect.setField(op, "maxDownloadRequestsBig", 8);
                            Reflect.setField(op, "maxCdnParts", (int) (1024L * 1024L * 2000L / (1024 * 512)));
                        }
                    } catch (Throwable ignored) {
                    }
                    return result;
                });
            }
            info("Enhancement: hooked FileLoadOperation.updateParams for download speed");
        } catch (Throwable throwable) {
            error("Enhancement: failed to hook download speed", throwable);
        }
    }

    private void hookLocalPremium(ClassLoader classLoader) {
        // Port of TeleVip TelePremium: UserConfig.isPremium() -> true.
        // Client-side only, see the header comment above.
        // Expanded 2026-10-25 for Build #7 feedback "pin >5 not working":
        // Conso also gates Premium features through
        // MessagesController.isPremiumUser(User),
        // MessagesController.premiumFeaturesBlocked(), and
        // DialogsActivity.showPremiumBlockedToast(). Hook all of them so a
        // client-side Premium check cannot fail on a different gate.
        // NOTE: if the server still rejects the 6th pin
        // (messages.toggleDialogPin error), no client hook can force it:
        // the server knows the account is not really Premium. That case
        // must be reported as a server limit, not a client bug.
        hookBooleanMethod(classLoader, "org.telegram.messenger.UserConfig", "isPremium",
                () -> currentEnhancementConfig().localPremium, true);
        hookBooleanMethod(classLoader, "org.telegram.messenger.MessagesController", "isPremiumUser",
                () -> currentEnhancementConfig().localPremium, true);
        hookBooleanMethod(classLoader, "org.telegram.messenger.MessagesController", "premiumFeaturesBlocked",
                () -> currentEnhancementConfig().localPremium, false);
        try {
            Class<?> clazz = classLoader.loadClass("org.telegram.ui.DialogsActivity");
            for (Method candidate : clazz.getDeclaredMethods()) {
                if (!"showPremiumBlockedToast".equals(candidate.getName())) {
                    continue;
                }
                candidate.setAccessible(true);
                Method method = candidate;
                hook(method, chain -> {
                    if (currentEnhancementConfig().localPremium) {
                        return null;
                    }
                    return chain.proceed();
                });
            }
            info("Enhancement: hooked DialogsActivity.showPremiumBlockedToast");
        } catch (Throwable throwable) {
            error("Enhancement: failed to hook showPremiumBlockedToast", throwable);
        }
    }


    // ------------------------------------------------------------------
    // Auto-translate to Chinese (Build #5, second push): uses Conso's own
    // TranslateController (org.telegram.messenger.TranslateController,
    // verified in Conso 2.0.0 dex: getTranslateController(),
    // setDialogTranslateTo(long, String), toggleTranslatingDialog(long,
    // boolean), isChatTranslateEnabled(), isFeatureAvailable(),
    // isChatAutoTranslated(long), getLanguages() with Language.code).
    // No third-party translation service is involved: the message goes
    // through Telegram's own translation path, the same one the host's
    // Translate button already uses. Whole-chat auto-translate is a
    // Premium-gated feature in the host, which is why the local-premium
    // switch (in the Enhancements card) helps here; without it, the
    // per-chat Translate button should still work.
    // ------------------------------------------------------------------
    private volatile String cachedZhCode = null;

    private String resolveZhCode(Object translateController) {
        if (cachedZhCode != null) {
            return cachedZhCode;
        }
        // 2026-10-25 fix for Build #9 feedback "translation exists but is
        // not correct": the old code took the FIRST zh* code from the host
        // language list, which can be Traditional Chinese (zh-TW / zh-Hant)
        // or a generic "zh" that the server maps unpredictably. Prefer
        // Simplified Chinese explicitly, in order, and never pick a
        // Traditional variant when a Simplified one exists.
        try {
            Object langs = Reflect.invokeIfExists(translateController, "getLanguages", new Class<?>[0]);
            if (langs instanceof List<?>) {
                List<String> codes = new ArrayList<>();
                for (Object lang : (List<?>) langs) {
                    String code = Reflect.asString(Reflect.field(lang, "code"));
                    if (code != null && !code.isBlank()) {
                        codes.add(code);
                    }
                }
                String[] preferred = new String[]{"zh-CN", "zh-cn", "zh_CN", "zh-Hans", "zh-Hans-CN", "zh"};
                for (String want : preferred) {
                    for (String code : codes) {
                        if (want.equalsIgnoreCase(code)) {
                            cachedZhCode = code;
                            return code;
                        }
                    }
                }
                // Fallback: any zh* that is not Traditional.
                for (String code : codes) {
                    String lower = code.toLowerCase(Locale.ROOT);
                    if (lower.startsWith("zh") && !lower.contains("tw") && !lower.contains("hant") && !lower.contains("hk")) {
                        cachedZhCode = code;
                        return code;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        cachedZhCode = "zh-CN";
        return cachedZhCode;
    }

    private void enableAutoTranslateForChat(Object chatActivity) {
        try {
            if (!currentEnhancementConfig().autoTranslateToChinese) {
                return;
            }
            long dialogId = Reflect.asLong(Reflect.invokeIfExists(chatActivity, "getDialogId", new Class<?>[0]), 0L);
            if (dialogId == 0L) {
                return;
            }
            int account = Reflect.asInt(Reflect.field(chatActivity, "currentAccount"), -1);
            if (account < 0 && savedClassLoader != null) {
                account = resolveSelectedTelegramAccount(savedClassLoader);
            }
            if (account < 0) {
                account = 0;
            }
            ClassLoader cl = savedClassLoader != null ? savedClassLoader : chatActivity.getClass().getClassLoader();
            Class<?> mcClass = cl.loadClass("org.telegram.messenger.MessagesController");
            Object controller = Reflect.invokeStatic(mcClass, "getInstance", new Class<?>[]{int.class}, account);
            Object tc = Reflect.invokeIfExists(controller, "getTranslateController", new Class<?>[0]);
            if (tc == null) {
                return;
            }
            String zh = resolveZhCode(tc);
            Reflect.invokeIfExists(tc, "setChatTranslateEnabled", new Class<?>[]{boolean.class}, true);
            Reflect.invokeIfExists(tc, "setDialogTranslateTo", new Class<?>[]{long.class, String.class}, dialogId, zh);
            Object toggled = Reflect.invokeIfExists(tc, "toggleTranslatingDialog", new Class<?>[]{long.class, boolean.class}, dialogId, true);
            if (toggled == null || Boolean.FALSE.equals(toggled)) {
                Reflect.invokeIfExists(tc, "toggleTranslatingDialog", new Class<?>[]{long.class}, dialogId);
            }
            // 2026-10-25 fix for Build #7 feedback "no translate item in
            // chat, no translation": toggling alone does not refresh the
            // chat UI. Ask the ChatActivity itself to re-check translation
            // for visible messages and to refresh the translate button.
            Reflect.invokeIfExists(chatActivity, "checkTranslation", new Class<?>[]{boolean.class}, true);
            Reflect.invokeIfExists(chatActivity, "updateTranslateItemVisibility", new Class<?>[0]);
            info("Auto-translate: enabled for dialog " + dialogId + " to " + zh);
        } catch (Throwable throwable) {
            error("Auto-translate enable failed", throwable);
        }
    }

    private void hookAutoTranslate(ClassLoader classLoader) {
        hookTranslatedLinkRestore(classLoader);
        hookBooleanMethod(classLoader, "org.telegram.messenger.TranslateController", "isChatTranslateEnabled",
                () -> currentEnhancementConfig().autoTranslateToChinese, true);
        hookBooleanMethod(classLoader, "org.telegram.messenger.TranslateController", "isFeatureAvailable",
                () -> currentEnhancementConfig().autoTranslateToChinese, true);
        hookBooleanMethod(classLoader, "org.telegram.messenger.TranslateController", "isChatAutoTranslated",
                () -> currentEnhancementConfig().autoTranslateToChinese, true);
        hookBooleanMethod(classLoader, "org.telegram.messenger.TranslateController", "isDialogTranslatable",
                () -> currentEnhancementConfig().autoTranslateToChinese, true);
        // Force the chat translate button/item visible after the host
        // recomputes visibility, so the translate item actually appears
        // in the chat interface when auto-translate is on.
        try {
            Class<?> clazz = classLoader.loadClass("org.telegram.ui.ChatActivity");
            for (Method candidate : clazz.getDeclaredMethods()) {
                if (!"updateTranslateItemVisibility".equals(candidate.getName())) {
                    continue;
                }
                candidate.setAccessible(true);
                Method method = candidate;
                hook(method, chain -> {
                    Object result = chain.proceed();
                    try {
                        if (currentEnhancementConfig().autoTranslateToChinese) {
                            Object chatActivity = chain.getThisObject();
                            Object translateItem = Reflect.field(chatActivity, "translateItem");
                            if (translateItem instanceof android.view.View) {
                                ((android.view.View) translateItem).setVisibility(android.view.View.VISIBLE);
                            }
                            Object translateButton = Reflect.field(chatActivity, "translateButton");
                            if (translateButton instanceof android.view.View) {
                                ((android.view.View) translateButton).setVisibility(android.view.View.VISIBLE);
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                    return result;
                });
            }
            info("Enhancement: hooked ChatActivity.updateTranslateItemVisibility");
        } catch (Throwable throwable) {
            error("Enhancement: failed to hook updateTranslateItemVisibility", throwable);
        }
        try {
            Class<?> clazz = classLoader.loadClass("org.telegram.messenger.TranslateController");
            for (Method candidate : clazz.getDeclaredMethods()) {
                if (!"getDialogTranslateTo".equals(candidate.getName())) {
                    continue;
                }
                candidate.setAccessible(true);
                Method method = candidate;
                hook(method, chain -> {
                    if (currentEnhancementConfig().autoTranslateToChinese) {
                        try {
                            return resolveZhCode(chain.getThisObject());
                        } catch (Throwable ignored) {
                            return "zh-CN";
                        }
                    }
                    return chain.proceed();
                });
            }
            info("Enhancement: hooked TranslateController for auto-translate to Chinese");
        } catch (Throwable throwable) {
            error("Enhancement: failed to hook TranslateController", throwable);
        }
    }

    private void hook(Method method, XposedInterface.Hooker hooker) {
        module.hook(method)
                .setPriority(XposedInterface.PRIORITY_LOWEST)
                .setExceptionMode(XposedInterface.ExceptionMode.DEFAULT)
                .intercept(hooker);
    }

    private void deoptimize(Method method, String label) {
        try {
            boolean changed = module.deoptimize(method);
            info((changed ? "Deoptimized " : "Deopt not needed for ") + label);
        } catch (Throwable throwable) {
            error("Failed to deoptimize " + label, throwable);
        }
    }

    private void info(String message) {
        ModuleLogger.hook(TAG, message);
    }

    private void error(String message, Throwable throwable) {
        ModuleLogger.hookError(TAG, message, throwable);
    }
}
