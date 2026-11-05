package io.github.iamr0s.carvex

import android.content.Context
import android.content.Intent
import android.net.Uri
import io.github.iamr0s.carvex.sessioncore.BondedDevices

/** One pass after install. System dialogs still need a tap; this only starts them. */
object FirstRun {
    private const val PREFS = "first_run"
    private const val DONE = "done"
    const val OFFICIAL_CARLIFE = "com.baidu.carlife"
    private const val CARWITH = "com.miui.carlink"

    fun pending(context: Context): Boolean =
        !context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(DONE, false)

    fun markDone(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(DONE, true).apply()
    }

    fun officialInstalled(context: Context): Boolean {
        if (context.packageName == OFFICIAL_CARLIFE) return false
        return runCatching {
            context.packageManager.getPackageInfo(OFFICIAL_CARLIFE, 0)
            true
        }.getOrDefault(false)
    }

    fun uninstallOfficial(): Intent =
        Intent(Intent.ACTION_DELETE, Uri.parse("package:$OFFICIAL_CARLIFE"))

    /** The car-hotspot page is not exported. Open the public CarWith settings entry. */
    fun openCarWith(context: Context): Boolean {
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK
        val settings = Intent("miui.intent.action.UCAR_APP_SETTINGS").setPackage(CARWITH).addFlags(flags)
        if (runCatching { context.startActivity(settings) }.isSuccess) return true
        val launch = context.packageManager.getLaunchIntentForPackage(CARWITH) ?: return false
        return runCatching { context.startActivity(launch.addFlags(flags)) }.isSuccess
    }

    /** 只有一台经典或双模车机时才自动选。BLE 和类型不明的不猜。 */
    fun soleCar(devices: List<BondedDevices.Device>): BondedDevices.Device? =
        devices.filter {
            it.kind == BondedDevices.Kind.CAR &&
                (it.transport == BondedDevices.Transport.CLASSIC || it.transport == BondedDevices.Transport.DUAL)
        }.singleOrNull()
}
