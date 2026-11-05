package io.github.iamr0s.carvex.sessioncore

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager

/**
 * 系统已配对的蓝牙设备 —— 首页第二段的数据源。
 *
 * 「已配对」不等于「本应用连过」：用户可能在系统设置里配对过车机，却从没用本应用连过。
 * 所以它与 [DeviceHistory] 是两套数据，首页按 [DeviceHistory.partition] 去重后分别展示。
 */
object BondedDevices {
    /** 权限是否就绪。API 31+ 需要 BLUETOOTH_CONNECT，以下用旧权限（安装即授）。 */
    fun permissionGranted(context: Context): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S)
            // 用框架 API 而不是 ContextCompat：本模块不依赖 androidx.core，
            // 为了一个权限查询引入整个 core-ktx 不值得。
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        else true

    fun bluetoothEnabled(context: Context): Boolean = runCatching {
        val manager = context.getSystemService(BluetoothManager::class.java) ?: return@runCatching false
        manager.adapter?.isEnabled == true
    }.getOrDefault(false)

    /**
     * 已配对设备列表。
     *
     * 返回 [Device] 而不是裸 Pair：首页要按设备类别选图标，把耳机/音箱都画成一台车
     * 是实测出来的错误观感（用户看到自己的蓝牙耳机被当成车机）。
     *
     * 任何一步不可用都返回空列表而不抛异常：首页在权限缺失/蓝牙关闭时应显示
     * "空列表 + 一句说明"，而不是崩溃或白屏。地址为空的条目直接丢弃 —— 没有地址无法连接。
     */
    fun list(context: Context): List<Device> {
        if (!permissionGranted(context)) return emptyList()
        return runCatching {
            val manager = context.getSystemService(BluetoothManager::class.java) ?: return@runCatching emptyList()
            val adapter: BluetoothAdapter = manager.adapter ?: return@runCatching emptyList()
            if (!adapter.isEnabled) return@runCatching emptyList()
            // getBondedDevices 在 API 31+ 上由 BLUETOOTH_CONNECT 把关，上面已检查。
            adapter.bondedDevices.orEmpty()
                .mapNotNull { device ->
                    // 地址统一 uppercase：它会被写进 options.target，而
                    // checkBluetoothAddress 大小写敏感（平台源码只认 A~F）。
                    // 系统返回的已配对地址通常已经是大写，但这里不能"通常"——
                    // 一旦某个 ROM 返回小写，就会复现"选好车机却说请先选择车机"的怪事。
                    val address = device.address?.takeIf { it.isNotBlank() }
                        ?.uppercase() ?: return@mapNotNull null
                    // 名字可能为空（少数车机不广播名称）；用地址兜底，避免列表出现空行。
                    val name = device.name?.takeIf { it.isNotBlank() } ?: address
                    Device(name, address, kind(device), transport(device))
                }
                // 车机排在耳机/音箱前面：这一屏的目的是连车，不是管理音频配件。
                .sortedWith(compareBy({ if (it.kind == Kind.CAR) 0 else 1 }, { it.name.lowercase() }))
        }.getOrDefault(emptyList())
    }

    enum class Kind { CAR, AUDIO, PHONE, OTHER }

    /** 经典/双模才能走 CarPlay 的 RFCOMM。只走 BLE 的配件不能当自动连接目标。 */
    enum class Transport { CLASSIC, LE, DUAL, UNKNOWN }

    data class Device(
        val name: String,
        val address: String,
        val kind: Kind,
        val transport: Transport = Transport.UNKNOWN,
    )

    /**
     * 从 BluetoothClass 判断类别。
     *
     * 注意 BluetoothClass 经常是 0（很多车机不填），所以**默认按 CAR 处理**：
     * 宁可把未知设备当车机（用户点一下就知道），也好过把真车机画成耳机。
     * 只有明确报告为音频/手机/电脑的，才换图标。
     */
    private fun kind(device: BluetoothDevice): Kind = runCatching {
        val cls = device.bluetoothClass ?: return@runCatching Kind.CAR
        when (cls.majorDeviceClass) {
            BluetoothClass.Device.Major.AUDIO_VIDEO -> Kind.AUDIO
            BluetoothClass.Device.Major.PHONE -> Kind.PHONE
            BluetoothClass.Device.Major.COMPUTER -> Kind.OTHER
            // PERIPHERAL 里同时包含键盘鼠标与车机套件，无法细分，按 CAR 处理更安全。
            else -> Kind.CAR
        }
    }.getOrDefault(Kind.CAR)

    private fun transport(device: BluetoothDevice): Transport = runCatching {
        when (device.type) {
            BluetoothDevice.DEVICE_TYPE_CLASSIC -> Transport.CLASSIC
            BluetoothDevice.DEVICE_TYPE_LE -> Transport.LE
            BluetoothDevice.DEVICE_TYPE_DUAL -> Transport.DUAL
            else -> Transport.UNKNOWN
        }
    }.getOrDefault(Transport.UNKNOWN)
}
