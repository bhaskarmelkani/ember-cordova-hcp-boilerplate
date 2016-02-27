package com.nordnetab.chcp.main;

import android.os.Handler;
import android.text.TextUtils;
import android.util.Log;

import com.nordnetab.chcp.main.config.ApplicationConfig;
import com.nordnetab.chcp.main.config.ChcpXmlConfig;
import com.nordnetab.chcp.main.config.ContentConfig;
import com.nordnetab.chcp.main.config.PluginInternalPreferences;
import com.nordnetab.chcp.main.events.AssetsInstallationErrorEvent;
import com.nordnetab.chcp.main.events.AssetsInstalledEvent;
import com.nordnetab.chcp.main.events.NothingToInstallEvent;
import com.nordnetab.chcp.main.events.NothingToUpdateEvent;
import com.nordnetab.chcp.main.events.UpdateDownloadErrorEvent;
import com.nordnetab.chcp.main.events.UpdateInstallationErrorEvent;
import com.nordnetab.chcp.main.events.UpdateInstalledEvent;
import com.nordnetab.chcp.main.events.UpdateIsReadyToInstallEvent;
import com.nordnetab.chcp.main.js.JSAction;
import com.nordnetab.chcp.main.js.PluginResultHelper;
import com.nordnetab.chcp.main.model.ChcpError;
import com.nordnetab.chcp.main.model.PluginFilesStructure;
import com.nordnetab.chcp.main.model.UpdateTime;
import com.nordnetab.chcp.main.storage.ApplicationConfigStorage;
import com.nordnetab.chcp.main.storage.IObjectFileStorage;
import com.nordnetab.chcp.main.storage.IObjectPreferenceStorage;
import com.nordnetab.chcp.main.storage.PluginInternalPreferencesStorage;
import com.nordnetab.chcp.main.updater.UpdatesInstaller;
import com.nordnetab.chcp.main.updater.UpdatesLoader;
import com.nordnetab.chcp.main.utils.AssetsHelper;
import com.nordnetab.chcp.main.utils.CleanUpHelper;
import com.nordnetab.chcp.main.utils.Paths;
import com.nordnetab.chcp.main.utils.VersionHelper;
import com.nordnetab.chcp.main.view.AppUpdateRequestDialog;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.ConfigXmlParser;
import org.apache.cordova.CordovaArgs;
import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CordovaWebView;
import org.apache.cordova.PluginResult;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;

import de.greenrobot.event.EventBus;
import de.greenrobot.event.Subscribe;

/**
 * Created by Nikolay Demyankov on 23.07.15.
 * <p/>
 * Plugin main class.
 */
public class HotCodePushPlugin extends CordovaPlugin {

    private static final String FILE_PREFIX = "file://";
    private static final String WWW_FOLDER = "www";
    private static final String LOCAL_ASSETS_FOLDER = "file:///android_asset/www";

    private String startingPage;
    private IObjectFileStorage<ApplicationConfig> appConfigStorage;
    private PluginInternalPreferences pluginInternalPrefs;
    private IObjectPreferenceStorage<PluginInternalPreferences> pluginInternalPrefsStorage;
    private ChcpXmlConfig chcpXmlConfig;
    private PluginFilesStructure fileStructure;

    private CallbackContext installJsCallback;
    private CallbackContext jsDefaultCallback;
    private CallbackContext downloadJsCallback;

    private Handler handler;
    private boolean isPluginReadyForWork;
    private boolean dontReloadOnStart;

    // region Plugin lifecycle

    @Override
    public void initialize(final CordovaInterface cordova, final CordovaWebView webView) {
        super.initialize(cordova, webView);

        parseCordovaConfigXml();
        loadPluginInternalPreferences();

        Log.d("CHCP", "Currently running release version " + pluginInternalPrefs.getCurrentReleaseVersionName());

        // clean up file system
        if (!TextUtils.isEmpty(pluginInternalPrefs.getCurrentReleaseVersionName())) {
            CleanUpHelper.removeReleaseFolders(cordova.getActivity(),
                    new String[]{pluginInternalPrefs.getCurrentReleaseVersionName(),
                            pluginInternalPrefs.getPreviousReleaseVersionName(),
                            pluginInternalPrefs.getReadyForInstallationReleaseVersionName()
                    }
            );
        }

        handler = new Handler();
        fileStructure = new PluginFilesStructure(cordova.getActivity(), pluginInternalPrefs.getCurrentReleaseVersionName());
        appConfigStorage = new ApplicationConfigStorage();
    }

    @Override
    public void onStart() {
        super.onStart();
        
        final EventBus eventBus = EventBus.getDefault();
        if (!eventBus.isRegistered(this)) {
            eventBus.register(this);
        }
        
        // ensure that www folder installed on external storage;
        // if not - install it
        isPluginReadyForWork = isPluginReadyForWork();
        if (!isPluginReadyForWork) {
            dontReloadOnStart = true;
            installWwwFolder();
            return;
        }

        // reload only if we on local storage
        if (!dontReloadOnStart) {
            dontReloadOnStart = true;
            redirectToLocalStorageIndexPage();
        }

        // install update if there is anything to install
        if (chcpXmlConfig.isAutoInstallIsAllowed() &&
                !UpdatesInstaller.isInstalling() &&
                !UpdatesLoader.isExecuting() &&
                !TextUtils.isEmpty(pluginInternalPrefs.getReadyForInstallationReleaseVersionName())) {
            installUpdate(null);
        }
    }

    @Override
    public void onResume(boolean multitasking) {
        super.onResume(multitasking);

        if (!isPluginReadyForWork) {
            return;
        }

        if (!chcpXmlConfig.isAutoInstallIsAllowed() ||
                UpdatesInstaller.isInstalling() ||
                UpdatesLoader.isExecuting() ||
                TextUtils.isEmpty(pluginInternalPrefs.getReadyForInstallationReleaseVersionName())) {
            return;
        }

        final PluginFilesStructure fs = new PluginFilesStructure(cordova.getActivity(), pluginInternalPrefs.getReadyForInstallationReleaseVersionName());
        final ApplicationConfig appConfig = appConfigStorage.loadFromFolder(fs.getDownloadFolder());
        if (appConfig == null) {
            return;
        }

        final UpdateTime updateTime = appConfig.getContentConfig().getUpdateTime();
        if (updateTime == UpdateTime.ON_RESUME || updateTime == UpdateTime.NOW) {
            installUpdate(null);
        }
    }

    @Override
    public void onStop() {
        EventBus.getDefault().unregister(this);

        super.onStop();
    }

    // endregion

    // region Config loaders and initialization

    /**
     * Read hot-code-push plugin preferences from cordova config.xml
     *
     * @see ChcpXmlConfig
     */
    private void parseCordovaConfigXml() {
        if (chcpXmlConfig != null) {
            return;
        }

        chcpXmlConfig = ChcpXmlConfig.loadFromCordovaConfig(cordova.getActivity());
    }

    /**
     * Load plugin internal preferences.
     *
     * @see PluginInternalPreferences
     * @see PluginInternalPreferencesStorage
     */
    private void loadPluginInternalPreferences() {
        if (pluginInternalPrefs != null) {
            return;
        }

        pluginInternalPrefsStorage = new PluginInternalPreferencesStorage(cordova.getActivity());
        PluginInternalPreferences config = pluginInternalPrefsStorage.loadFromPreference();
        if (config == null || TextUtils.isEmpty(config.getCurrentReleaseVersionName())) {
            config = PluginInternalPreferences.createDefault(cordova.getActivity());
            pluginInternalPrefsStorage.storeInPreference(config);
        }
        pluginInternalPrefs = config;
    }

    // endregion

    // region JavaScript processing

    @Override
    public boolean execute(String action, CordovaArgs args, CallbackContext callbackContext) throws JSONException {
        boolean cmdProcessed = true;
        if (JSAction.INIT.equals(action)) {
            jsInit(callbackContext);
        } else if (JSAction.FETCH_UPDATE.equals(action)) {
            jsFetchUpdate(callbackContext);
        } else if (JSAction.INSTALL_UPDATE.equals(action)) {
            jsInstallUpdate(callbackContext);
        } else if (JSAction.CONFIGURE.equals(action)) {
            jsSetPluginOptions(args, callbackContext);
        } else if (JSAction.REQUEST_APP_UPDATE.equals(action)) {
            jsRequestAppUpdate(args, callbackContext);
        } else {
            cmdProcessed = false;
        }

        return cmdProcessed;
    }

    /**
     * Send message to default plugin callback.
     * Default callback - is a callback that we receive on initialization (device ready).
     * Through it we are broadcasting different events.
     *
     * @param message message to send to web side
     */
    private void sendMessageToDefaultCallback(PluginResult message) {
        if (jsDefaultCallback == null) {
            return;
        }

        message.setKeepCallback(true);
        jsDefaultCallback.sendPluginResult(message);
    }

    /**
     * Initialize default callback, received from the web side.
     *
     * @param callback callback to use for events broadcasting
     */
    private void jsInit(CallbackContext callback) {
        jsDefaultCallback = callback;

        // Clear web history.
        // In some cases this is necessary, because on the launch we redirect user to the
        // external storage. And if he presses back button - browser will lead him back to
        // assets folder, which we don't want.
        handler.post(new Runnable() {
            @Override
            public void run() {
                webView.clearHistory();

            }
        });

        // fetch update when we are initialized
        if (chcpXmlConfig.isAutoDownloadIsAllowed() &&
                !UpdatesInstaller.isInstalling() && !UpdatesLoader.isExecuting()) {
            fetchUpdate(null);
        }
    }

    /**
     * Check for update.
     * Method is called from JS side.
     *
     * @param callback js callback
     */
    private void jsFetchUpdate(CallbackContext callback) {
        if (!isPluginReadyForWork) {
            sendPluginNotReadyToWork(UpdateDownloadErrorEvent.EVENT_NAME, callback);
            return;
        }

        fetchUpdate(callback);
    }

    /**
     * Install the update.
     * Method is called from JS side.
     *
     * @param callback js callback
     */
    private void jsInstallUpdate(CallbackContext callback) {
        if (!isPluginReadyForWork) {
            sendPluginNotReadyToWork(UpdateInstallationErrorEvent.EVENT_NAME, callback);
            return;
        }

        installUpdate(callback);
    }

    /**
     * Send to JS side event with message, that plugin is installing assets on the external storage and not yet ready for work.
     * That happens only on the first launch.
     *
     * @param eventName event name, that is send to JS side
     * @param callback  JS callback
     */
    private void sendPluginNotReadyToWork(String eventName, CallbackContext callback) {
        PluginResult pluginResult = PluginResultHelper.createPluginResult(eventName, null, ChcpError.ASSETS_FOLDER_IN_NOT_YET_INSTALLED);
        callback.sendPluginResult(pluginResult);
    }

    /**
     * Set plugin options. Method is called from JavaScript.
     *
     * @param arguments arguments from JavaScript
     * @param callback  callback where to send result
     */
    private void jsSetPluginOptions(CordovaArgs arguments, CallbackContext callback) {
        if (!isPluginReadyForWork) {
            sendPluginNotReadyToWork("", callback);
            return;
        }

        try {
            JSONObject jsonObject = (JSONObject) arguments.get(0);
            chcpXmlConfig.mergeOptionsFromJs(jsonObject);
            // TODO: store them somewhere?
        } catch (JSONException e) {
            Log.d("CHCP", "Failed to process plugin options, received from JS.", e);
        }

        callback.success();
    }

    /**
     * Show dialog with request to update the application through the Google Play.
     *
     * @param arguments arguments from JavaScript
     * @param callback  callback where to send result
     */
    private void jsRequestAppUpdate(final CordovaArgs arguments, final CallbackContext callback) {
        if (!isPluginReadyForWork) {
            sendPluginNotReadyToWork("", callback);
            return;
        }

        String msg = null;
        try {
            msg = (String) arguments.get(0);
        } catch (JSONException e) {
            Log.d("CHCP", "Dialog message is not set", e);
        }

        if (TextUtils.isEmpty(msg)) {
            return;
        }

        final String storeURL = appConfigStorage.loadFromFolder(fileStructure.getWwwFolder()).getStoreUrl();

        new AppUpdateRequestDialog(cordova.getActivity(), msg, storeURL, callback).show();
    }

    /**
     * Perform update availability check.
     *
     * @param jsCallback callback where to send the result;
     *                   used, when update is requested manually from JavaScript
     */
    private void fetchUpdate(CallbackContext jsCallback) {
        if (!isPluginReadyForWork) {
            return;
        }

        ChcpError error = UpdatesLoader.downloadUpdate(cordova.getActivity(), chcpXmlConfig.getConfigUrl(), pluginInternalPrefs.getCurrentReleaseVersionName());
        if (error != ChcpError.NONE) {
            if (jsCallback != null) {
                PluginResult errorResult = PluginResultHelper.createPluginResult(UpdateDownloadErrorEvent.EVENT_NAME, null, error);
                jsCallback.sendPluginResult(errorResult);
            }
            return;
        }

        if (jsCallback != null) {
            downloadJsCallback = jsCallback;
        }
    }

    /**
     * Install update if any available.
     *
     * @param jsCallback callback where to send the result;
     *                   used, when installation os requested manually from JavaScript
     */
    private void installUpdate(CallbackContext jsCallback) {
        if (!isPluginReadyForWork) {
            return;
        }

        ChcpError error = UpdatesInstaller.install(cordova.getActivity(), pluginInternalPrefs.getReadyForInstallationReleaseVersionName(), pluginInternalPrefs.getCurrentReleaseVersionName());
        if (error != ChcpError.NONE) {
            if (jsCallback != null) {
                PluginResult errorResult = PluginResultHelper.createPluginResult(UpdateInstallationErrorEvent.EVENT_NAME, null, error);
                jsCallback.sendPluginResult(errorResult);
            }

            return;
        }

        if (jsCallback != null) {
            installJsCallback = jsCallback;
        }
    }

    // endregion

    // region Private API

    /**
     * Check if plugin can perform it's duties.
     *
     * @return <code>true</code> - plugin is ready; otherwise - <code>false</code>
     */
    private boolean isPluginReadyForWork() {
        boolean isWwwFolderExists = isWwwFolderExists();
        boolean isWwwFolderInstalled = pluginInternalPrefs.isWwwFolderInstalled();
        boolean isApplicationHasBeenUpdated = isApplicationHasBeenUpdated();

        return isWwwFolderExists && isWwwFolderInstalled && !isApplicationHasBeenUpdated;
    }

    /**
     * Check if external version of www folder exists.
     *
     * @return <code>true</code> if it is in place; <code>false</code> - otherwise
     */
    private boolean isWwwFolderExists() {
        return new File(fileStructure.getWwwFolder()).exists();
    }

    /**
     * Check if application has been updated through the Google Play since the last launch.
     *
     * @return <code>true</code> if application was update; <code>false</code> - otherwise
     */
    private boolean isApplicationHasBeenUpdated() {
        return pluginInternalPrefs.getAppBuildVersion() != VersionHelper.applicationVersionCode(cordova.getActivity());
    }

    /**
     * Install assets folder onto the external storage
     */
    private void installWwwFolder() {
        isPluginReadyForWork = false;

        // reset www folder installed flag
        if (pluginInternalPrefs.isWwwFolderInstalled()) {
            pluginInternalPrefs.setWwwFolderInstalled(false);
            pluginInternalPrefsStorage.storeInPreference(pluginInternalPrefs);
        }

        AssetsHelper.copyAssetDirectoryToAppDirectory(cordova.getActivity().getAssets(), WWW_FOLDER, fileStructure.getWwwFolder());
    }

    /**
     * Redirect user onto the page, that resides on the external storage instead of the assets folder.
     */
    private void redirectToLocalStorageIndexPage() {
        final String indexPage = getStartingPage();

        // remove query and fragment parameters from the index page path
        // TODO: cleanup this fragment
        String strippedIndexPage = indexPage;
        if (strippedIndexPage.contains("#") || strippedIndexPage.contains("?")) {
            int idx = strippedIndexPage.lastIndexOf("?");
            if (idx >= 0) {
                strippedIndexPage = strippedIndexPage.substring(0, idx);
            } else {
                idx = strippedIndexPage.lastIndexOf("#");
                strippedIndexPage = strippedIndexPage.substring(0, idx);
            }
        }

        // make sure, that index page exists
        String external = Paths.get(fileStructure.getWwwFolder(), strippedIndexPage);
        if (!new File(external).exists()) {
            Log.d("CHCP", "External starting page not found. Aborting page change.");
            return;
        }

        // load index page from the external source
        external = Paths.get(fileStructure.getWwwFolder(), indexPage);
        webView.loadUrlIntoView(FILE_PREFIX + external, false);

        Log.d("CHCP", "Loading external page: " + external);
    }

    /**
     * Getter for the startup page.
     *
     * @return startup page relative path
     */
    private String getStartingPage() {
        if (!TextUtils.isEmpty(startingPage)) {
            return startingPage;
        }

        ConfigXmlParser parser = new ConfigXmlParser();
        parser.parse(cordova.getActivity());
        String url = parser.getLaunchUrl();

        startingPage = url.replace(LOCAL_ASSETS_FOLDER, "");

        return startingPage;
    }

    // endregion

    // region Assets installation events

    /**
     * Listener for event that assets folder are now installed on the external storage.
     * From that moment all content will be displayed from it.
     *
     * @param event event details
     * @see AssetsInstalledEvent
     * @see AssetsHelper
     * @see EventBus
     */
    @SuppressWarnings("unused")
    @Subscribe
    public void onEvent(AssetsInstalledEvent event) {
        // update stored application version
        pluginInternalPrefs.setAppBuildVersion(VersionHelper.applicationVersionCode(cordova.getActivity()));
        pluginInternalPrefs.setWwwFolderInstalled(true);
        pluginInternalPrefsStorage.storeInPreference(pluginInternalPrefs);

        isPluginReadyForWork = true;

        PluginResult result = PluginResultHelper.pluginResultFromEvent(event);
        sendMessageToDefaultCallback(result);

        if (chcpXmlConfig.isAutoDownloadIsAllowed() &&
                !UpdatesInstaller.isInstalling() && !UpdatesLoader.isExecuting()) {
            fetchUpdate(null);
        }
    }

    /**
     * Listener for event that we failed to install assets folder on the external storage.
     * If so - nothing we can do, plugin is not gonna work.
     *
     * @param event event details
     * @see AssetsInstallationErrorEvent
     * @see AssetsHelper
     * @see EventBus
     */
    @SuppressWarnings("unused")
    @Subscribe
    public void onEvent(AssetsInstallationErrorEvent event) {
        Log.d("CHCP", "Can't install assets on device. Continue to work with default bundle");

        PluginResult result = PluginResultHelper.pluginResultFromEvent(event);
        sendMessageToDefaultCallback(result);
    }

    // endregion

    // region Update download events

    /**
     * Listener for the event that update is loaded and ready for the installation.
     *
     * @param event event information
     * @see EventBus
     * @see UpdateIsReadyToInstallEvent
     * @see UpdatesLoader
     */
    @SuppressWarnings("unused")
    @Subscribe
    public void onEvent(UpdateIsReadyToInstallEvent event) {
        final ContentConfig newContentConfig = event.applicationConfig().getContentConfig();
        Log.d("CHCP", "Update is ready for installation: " + newContentConfig.getReleaseVersion());

        pluginInternalPrefs.setReadyForInstallationReleaseVersionName(newContentConfig.getReleaseVersion());
        pluginInternalPrefsStorage.storeInPreference(pluginInternalPrefs);

        PluginResult jsResult = PluginResultHelper.pluginResultFromEvent(event);

        // notify JS
        if (downloadJsCallback != null) {
            downloadJsCallback.sendPluginResult(jsResult);
            downloadJsCallback = null;
        }

        sendMessageToDefaultCallback(jsResult);

        // perform installation if allowed
        if (chcpXmlConfig.isAutoInstallIsAllowed() && newContentConfig.getUpdateTime() == UpdateTime.NOW) {
            installUpdate(null);
        }
    }

    /**
     * Listener for event that there is no update available at the moment.
     * We are as fresh as possible.
     *
     * @param event event information
     * @see EventBus
     * @see NothingToUpdateEvent
     * @see UpdatesLoader
     */
    @SuppressWarnings("unused")
    @Subscribe
    public void onEvent(NothingToUpdateEvent event) {
        Log.d("CHCP", "Nothing to update");

        PluginResult jsResult = PluginResultHelper.pluginResultFromEvent(event);

        //notify JS
        if (downloadJsCallback != null) {
            downloadJsCallback.sendPluginResult(jsResult);
            downloadJsCallback = null;
        }

        sendMessageToDefaultCallback(jsResult);
    }

    /**
     * Listener for event that some error has happened during the update download process.
     *
     * @param event event information
     * @see EventBus
     * @see UpdateDownloadErrorEvent
     * @see UpdatesLoader
     */
    @SuppressWarnings("unused")
    @Subscribe
    public void onEvent(UpdateDownloadErrorEvent event) {
        Log.d("CHCP", "Failed to update");

        final ChcpError error = event.error();
        if (error == ChcpError.LOCAL_VERSION_OF_APPLICATION_CONFIG_NOT_FOUND || error == ChcpError.LOCAL_VERSION_OF_MANIFEST_NOT_FOUND) {
            Log.d("CHCP", "Can't load application config from installation folder. Reinstalling external folder");
            installWwwFolder();
        }

        PluginResult jsResult = PluginResultHelper.pluginResultFromEvent(event);

        // notify JS
        if (downloadJsCallback != null) {
            downloadJsCallback.sendPluginResult(jsResult);
            downloadJsCallback = null;
        }

        sendMessageToDefaultCallback(jsResult);

        rollbackIfCorrupted(event.error());
    }

    // endregion

    // region Update installation events

    /**
     * Listener for event that we successfully installed new update.
     *
     * @param event event information
     * @see EventBus
     * @see UpdateInstalledEvent
     * @see UpdatesInstaller
     */
    @SuppressWarnings("unused")
    @Subscribe
    public void onEvent(UpdateInstalledEvent event) {
        Log.d("CHCP", "Update is installed");

        final ContentConfig newContentConfig = event.applicationConfig().getContentConfig();

        // update preferences
        pluginInternalPrefs.setPreviousReleaseVersionName(pluginInternalPrefs.getCurrentReleaseVersionName());
        pluginInternalPrefs.setCurrentReleaseVersionName(newContentConfig.getReleaseVersion());
        pluginInternalPrefs.setReadyForInstallationReleaseVersionName("");
        pluginInternalPrefsStorage.storeInPreference(pluginInternalPrefs);

        fileStructure = new PluginFilesStructure(cordova.getActivity(), newContentConfig.getReleaseVersion());

        final PluginResult jsResult = PluginResultHelper.pluginResultFromEvent(event);

        if (installJsCallback != null) {
            installJsCallback.sendPluginResult(jsResult);
            installJsCallback = null;
        }

        sendMessageToDefaultCallback(jsResult);

        // reset content to index page
        handler.post(new Runnable() {
            @Override
            public void run() {
                HotCodePushPlugin.this.redirectToLocalStorageIndexPage();
            }
        });
    }

    /**
     * Listener for event that some error happened during the update installation.
     *
     * @param event event information
     * @see UpdateInstallationErrorEvent
     * @see EventBus
     * @see UpdatesInstaller
     */
    @SuppressWarnings("unused")
    @Subscribe
    public void onEvent(UpdateInstallationErrorEvent event) {
        Log.d("CHCP", "Failed to install");

        PluginResult jsResult = PluginResultHelper.pluginResultFromEvent(event);

        // notify js
        if (installJsCallback != null) {
            installJsCallback.sendPluginResult(jsResult);
            installJsCallback = null;
        }

        sendMessageToDefaultCallback(jsResult);

        rollbackIfCorrupted(event.error());
    }

    /**
     * Listener for event that there is nothing to install.
     *
     * @param event event information
     * @see NothingToInstallEvent
     * @see UpdatesInstaller
     * @see EventBus
     */
    @SuppressWarnings("unused")
    @Subscribe
    public void onEvent(NothingToInstallEvent event) {
        Log.d("CHCP", "Nothing to install");

        PluginResult jsResult = PluginResultHelper.pluginResultFromEvent(event);

        // notify JS
        if (installJsCallback != null) {
            installJsCallback.sendPluginResult(jsResult);
            installJsCallback = null;
        }

        sendMessageToDefaultCallback(jsResult);
    }

    // endregion

    // region Rollback process

    /**
     * Rollback to the previous/bundle version, if this is needed.
     *
     * @param error error, based on which we will decide
     */
    private void rollbackIfCorrupted(ChcpError error) {
        if (error != ChcpError.LOCAL_VERSION_OF_APPLICATION_CONFIG_NOT_FOUND &&
                error != ChcpError.LOCAL_VERSION_OF_MANIFEST_NOT_FOUND) {
            return;
        }

        if (pluginInternalPrefs.getPreviousReleaseVersionName().length() > 0) {
            Log.d("CHCP", "Current release is corrupted, trying to rollback to the previous one");
            rollbackToPreviousRelease();
        } else {
            Log.d("CHCP", "Current release is corrupted, reinstalling www folder from assets");
            installWwwFolder();
        }
    }

    /**
     * Rollback to the previously installed version of the web content.
     */
    private void rollbackToPreviousRelease() {
        pluginInternalPrefs.setCurrentReleaseVersionName(pluginInternalPrefs.getPreviousReleaseVersionName());
        pluginInternalPrefs.setPreviousReleaseVersionName("");
        pluginInternalPrefs.setReadyForInstallationReleaseVersionName("");
        pluginInternalPrefsStorage.storeInPreference(pluginInternalPrefs);

        fileStructure.switchToRelease(pluginInternalPrefs.getCurrentReleaseVersionName());

        handler.post(new Runnable() {
            @Override
            public void run() {
                redirectToLocalStorageIndexPage();
            }
        });
    }

    // endregion
}
