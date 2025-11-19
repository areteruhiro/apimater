package com.nonnonstop.apimate

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.core.content.FileProvider
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.snackbar.Snackbar

import rikka.shizuku.Shizuku
import rikka.shizuku.shared.BuildConfig
import timber.log.Timber
import java.io.File

class SettingsFragment : PreferenceFragmentCompat() {

    private lateinit var scripts: Scripts

    companion object {
        private const val REQUEST_CODE_SHIZUKU = 100
        private const val PREF_KEY_ANDROID_DATA_DIR = "android_data_dir"

        // ★ 固定したい A2chMate の DAT ディレクトリ
        private const val A2CHMATE_DAT_DIR =
            "/storage/emulated/0/Android/data/jp.co.airfront.android.a2chMate/files/2chMate/DAT"
    }

    // Shizuku 権限リクエストの結果を受け取るリスナー
    private val shizukuPermissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == REQUEST_CODE_SHIZUKU) {
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    Timber.d("Shizuku permission granted")
                    // 必要ならここで何か処理してもよい
                } else {
                    Timber.e("Shizuku permission denied")
                    view?.let {
                        Snackbar.make(it, "Shizuku の権限がありません", Snackbar.LENGTH_LONG).show()
                    }
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Shizuku の権限コールバック登録
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
    }

    override fun onDestroy() {
        // Shizuku の権限コールバック解除
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        super.onDestroy()
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.root_preferences, rootKey)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        scripts = Scripts(requireContext())
        onCreateDatPreference()
        onCreateOpenDefaultPreference()
        onCreateShowLogPreference()
        onCreateVersionPreference()
        onCreateFixedAndroidDataPreference()   // ★ A2chMate DAT 用の項目を初期化
    }

    /**
     * Shizuku が使えるか/権限があるかをチェックして、OK なら onGranted を実行
     */
    private fun ensureShizukuPermission(onGranted: () -> Unit) {
        // Shizuku サーバーが起動しているか
        if (!Shizuku.pingBinder()) {
            Snackbar.make(requireView(), "Shizuku が起動していません", Snackbar.LENGTH_LONG).show()
            return
        }

        val perm = Shizuku.checkSelfPermission()
        if (perm == PackageManager.PERMISSION_GRANTED) {
            onGranted()
        } else {
            Shizuku.requestPermission(REQUEST_CODE_SHIZUKU)
        }
    }

    /**
     * 元からある Dat 用の Preference
     */
    private fun onCreateDatPreference() {
        val preparePreference = findPreference<Preference>("prepare")!!
        try {
            val configState = scripts.onCreateDatPreference(this, preparePreference)
            preparePreference.setOnPreferenceClickListener {
                try {
                    scripts.onClickDatPreference(this, configState)
                } catch (ex: Exception) {
                    Timber.e(ex, "Failed to execute script (onClickDatPreference)")
                    Snackbar.make(
                        requireView(),
                        R.string.prepare_click_failed,
                        Snackbar.LENGTH_LONG
                    ).show()
                }
                true
            }
        } catch (ex: Exception) {
            Timber.e(ex, "Failed to execute script (onCreateDatPreference)")
            Snackbar.make(
                requireView(),
                R.string.prepare_create_failed,
                Snackbar.LENGTH_LONG
            ).show()
        }
    }

    /**
     * 「既定で開く」設定画面を開く Preference
     */
    private fun onCreateOpenDefaultPreference() {
        val preference = findPreference<Preference>("open_default")!!
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            preference.isEnabled = false
            return
        }
        preference.setOnPreferenceClickListener {
            val intent = Intent(
                Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS,
                Uri.parse("package:" + "")
            )
            startActivity(intent)
            true
        }
    }

    /**
     * ログファイルを FileProvider 経由で開く Preference
     */
    private fun onCreateShowLogPreference() {
        val context = requireContext()
        val logDir = context.externalCacheDir ?: return
        val uri = FileProvider.getUriForFile(
            context,
            "" + ".fileprovider",
            File(logDir, "exception0.txt")
        )
        val preference = findPreference<Preference>("show_log")!!
        preference.intent = Intent(Intent.ACTION_VIEW).apply {
            data = uri
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * バージョン情報を表示する Preference
     */
    private fun onCreateVersionPreference() {
        val versionPreference = findPreference<Preference>("version")!!
        versionPreference.summary = "${getString(R.string.app_name)} ${""}"
    }

    /**
     * ★ A2chMate の DAT ディレクトリを設定する Preference
     *
     * root_preferences.xml に
     *
     * <Preference
     *     android:key="android_data_dir"
     *     android:title="DAT ディレクトリ"
     *     android:summary="未設定" />
     *
     * などを追加しておくことを想定
     */
    private fun onCreateFixedAndroidDataPreference() {
        val dirPref = findPreference<Preference>(PREF_KEY_ANDROID_DATA_DIR) ?: return
        val prefs = preferenceManager.sharedPreferences

        // 既に保存済みなら summary に表示
        val saved = prefs?.getString(PREF_KEY_ANDROID_DATA_DIR, null)
        dirPref.summary = saved ?: "未設定"

        dirPref.setOnPreferenceClickListener {
            // クリック時に Shizuku 権限を確認してから固定パスを保存
            ensureShizukuPermission {
                // 必要ならここで存在チェック（Shizuku.newProcessで [ -d "$A2CHMATE_DAT_DIR" ] ...）してもOK

                if (prefs != null) {
                    prefs.edit()
                        .putString(PREF_KEY_ANDROID_DATA_DIR, A2CHMATE_DAT_DIR)
                        .apply()
                }
                dirPref.summary = A2CHMATE_DAT_DIR

                Snackbar.make(
                    requireView(),
                    "DAT ディレクトリを設定しました:\n$A2CHMATE_DAT_DIR",
                    Snackbar.LENGTH_LONG
                ).show()
            }
            true
        }
    }
}
