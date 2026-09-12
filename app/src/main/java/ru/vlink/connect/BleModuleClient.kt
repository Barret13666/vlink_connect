package ru.vlink.connect

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Настоящая связь с модулем.
 *
 * ГЛАВНОЕ, ЧТО ЗДЕСЬ ЕСТЬ: очередь операций. Android не выполняет две
 * операции GATT одновременно — вторая просто вернёт false и пропадёт молча.
 * А первичная настройка и смена пароля шлют по два кадра подряд, и потеря
 * второго выглядела бы как необъяснимый отказ. Поэтому каждая запись ждёт
 * своего onCharacteristicWrite, и только потом уходит следующая.
 *
 * Второе: события отправляются по одной сопрограмме на главном потоке, а не
 * каждое своей. Порядок доставки должен совпадать с порядком, в котором
 * модуль их прислал, иначе состояние экранов начинает скакать.
 */
@SuppressLint("MissingPermission")
class BleModuleClient(private val app: Application) : ModuleClient {

    private companion object {
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** Задержка перед поиском сервисов. Для привязанного устройства
         *  Android в этот момент поднимает шифрование, и опрос таблицы
         *  вперемешку с этим даёт то пустой список сервисов, то ошибку 133. */
        const val DISCOVER_DELAY_MS = 600L

        /** Сколько искать, прежде чем остановиться самому. Бесконечный
         *  поиск сажает батарею, а Android и вовсе глушит приложение,
         *  запустившее сканирование больше пяти раз за полминуты. */
        const val SCAN_MS = 15_000L
    }

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _link = MutableStateFlow(Link.IDLE)
    override val link: StateFlow<Link> = _link

    private val _found = MutableStateFlow<List<FoundModule>>(emptyList())
    override val found: StateFlow<List<FoundModule>> = _found

    private val _events = MutableSharedFlow<VescProtocol.Event>(extraBufferCapacity = 64)
    override val events: SharedFlow<VescProtocol.Event> = _events

    private val _notice = MutableSharedFlow<String>(extraBufferCapacity = 8)
    override val notice: SharedFlow<String> = _notice

    private val adapter: BluetoothAdapter?
        get() = (app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private var gatt: BluetoothGatt? = null
    private var ctrl: BluetoothGattCharacteristic? = null
    private var evt: BluetoothGattCharacteristic? = null

    private val outbox = ArrayDeque<ByteArray>()
    private var busy = false

    /** Отключились по своей воле. Нужно, чтобы не пугать человека
     *  сообщением «модуль закрыл связь», когда связь закрыл он сам. */
    private var closing = false

    private val stopScanTask = Runnable { stopScan() }

    private fun say(text: String) { scope.launch { _notice.emit(text) } }
    private fun push(e: VescProtocol.Event) { scope.launch { _events.emit(e) } }

    // ------------------------------------------------------------- поиск

    /**
     * Модуль это или мост. Смотрим сначала на объявленные сервисы, и только
     * если их в пакете не оказалось — на имя.
     *
     * Порядок такой не от хорошей жизни. Служебный UUID моста лежит в самой
     * рекламе, и его видно всегда. А NUS модуля Веддер кладёт в scan
     * response: на две 128-битные записи в рекламном пакете места нет.
     * Scan response доезжает при активном сканировании, но не на всяком
     * телефоне и не с первого пакета, поэтому имя остаётся запасным
     * признаком.
     */
    private fun kindOf(result: ScanResult, name: String): DeviceKind {
        val uuids = result.scanRecord?.serviceUuids
        if (uuids != null) {
            if (uuids.any { it.uuid == BridgeProtocol.SERVICE }) return DeviceKind.BRIDGE
            if (uuids.any { it.uuid == VescProtocol.NUS }) return DeviceKind.MODULE
        }
        return if (name.contains("Bridge", ignoreCase = true)) DeviceKind.BRIDGE
               else DeviceKind.MODULE
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val dev = result.device ?: return
            val name = result.scanRecord?.deviceName ?: dev.name ?: "Без имени"
            val list = _found.value.toMutableList()
            val at = list.indexOfFirst { it.id == dev.address }
            val item = FoundModule(dev.address, name, result.rssi, kindOf(result, name))
            if (at >= 0) list[at] = item else list += item
            _found.value = list
        }

        override fun onScanFailed(errorCode: Int) {
            // Код 1 — «поиск уже идёт». Это не отказ: поиск работает, и
            // сбрасывать состояние в «не ищем» нельзя. Иначе кнопка снова
            // предложит искать, следующий запуск вернёт ту же единицу, и
            // выбраться можно будет только перезапуском приложения.
            if (errorCode == 1) {
                _link.value = Link.SCANNING
                return
            }

            _link.value = Link.IDLE
            say(when (errorCode) {
                2 -> "Android не дал приложению искать устройства. Помогает " +
                     "выключить и включить Bluetooth."
                3 -> "Внутренняя ошибка Bluetooth. Помогает выключить и " +
                     "включить его."
                4 -> "Этот телефон не умеет искать устройства BLE."
                5 -> "Bluetooth занят другими приложениями, закройте лишние."
                6 -> "Поиск запускался слишком часто — Android его придержал. " +
                     "Подождите около полуминуты."
                else -> "Поиск не запустился, код $errorCode"
            })
        }
    }

    override fun startScan() {
        val a = adapter
        if (a == null || !a.isEnabled) { say("Включите Bluetooth"); return }

        // Уже ищем — просто продлеваем. Повторный запуск вернул бы код 1, а
        // главное, Android считает запуски: пять за полминуты, и он глушит
        // поиск молча, без ошибки и без результатов. Потянуть список вниз
        // пять раз подряд — дело двух секунд.
        if (_link.value == Link.SCANNING) {
            main.removeCallbacks(stopScanTask)
            main.postDelayed(stopScanTask, SCAN_MS)
            return
        }

        // Снимаем возможную забытую подписку: если прошлый поиск закончился
        // не через нас, она осталась висеть, и запуск дал бы код 1.
        a.bluetoothLeScanner?.stopScan(scanCallback)

        _found.value = emptyList()
        _link.value = Link.SCANNING

        // Два фильтра, Android соединяет их по «или». Первый ловит модуль по
        // UUID моста в веск: служебного сервиса в пакете рекламы нет, места
        // на две 128-битные записи в нём не хватает. Второй ловит мост
        // UART-дисплея по его сервису настройки — этот лежит прямо в
        // рекламе, потому что имя у моста длинное и в один пакет с UUID
        // не влезает, так что разъехались по разным.
        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(VescProtocol.NUS))
                .build(),
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(BridgeProtocol.SERVICE))
                .build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        a.bluetoothLeScanner?.startScan(filters, settings, scanCallback)

        main.removeCallbacks(stopScanTask)
        main.postDelayed(stopScanTask, SCAN_MS)
    }

    override fun stopScan() {
        main.removeCallbacks(stopScanTask)
        adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        if (_link.value == Link.SCANNING) _link.value = Link.IDLE
    }

    // ------------------------------------------------------- подключение

    override fun connect(id: String) {
        val a = adapter ?: return say("Bluetooth недоступен")
        stopScan()
        _link.value = Link.CONNECTING
        closing = false
        val dev: BluetoothDevice = a.getRemoteDevice(id)
        gatt = dev.connectGatt(app, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    override fun disconnect() {
        closing = true
        main.removeCallbacksAndMessages(null)
        outbox.clear()
        busy = false
        ctrl = null
        evt = null
        gatt?.let { it.disconnect(); it.close() }
        gatt = null
        if (_link.value != Link.IDLE) _link.value = Link.IDLE
    }

    // -------------------------------------------------------- отправка

    override fun send(frame: ByteArray) {
        outbox.addLast(frame)
        pump()
    }

    private fun pump() {
        if (busy) return
        val g = gatt ?: return
        val c = ctrl ?: return
        val frame = outbox.removeFirstOrNull() ?: return
        busy = true

        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(
                c, frame, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            ) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                c.value = frame
                g.writeCharacteristic(c)
            }
        }

        if (!ok) {
            busy = false
            say("Команда не ушла в модуль")
        }
    }

    // -------------------------------------------------------- обратные вызовы

    private val callback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                main.postDelayed({ g.discoverServices() }, DISCOVER_DELAY_MS)
            } else {
                val wasReady = _link.value == Link.READY
                outbox.clear(); busy = false; ctrl = null; evt = null
                g.close()
                if (gatt === g) gatt = null

                if (closing) {
                    _link.value = Link.IDLE
                } else {
                    _link.value = Link.LOST
                    if (!wasReady && status != BluetoothGatt.GATT_SUCCESS) {
                        say("Не удалось подключиться, код $status")
                    }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val service = g.getService(VescProtocol.SERVICE)
            if (service == null) {
                say("У этого устройства нет служебного сервиса — " +
                    "похоже, это модуль с другой прошивкой")
                disconnect()
                return
            }
            ctrl = service.getCharacteristic(VescProtocol.CTRL)
            evt = service.getCharacteristic(VescProtocol.EVT)
            val e = evt
            if (ctrl == null || e == null) {
                say("Служебный сервис неполный")
                disconnect()
                return
            }

            // Подписка: сначала включаем у себя, потом пишем дескриптор и
            // ждём подтверждения. Слать команды раньше бессмысленно —
            // ответы придут в пустоту.
            g.setCharacteristicNotification(e, true)
            val cccd = e.getDescriptor(CCCD)
            if (cccd == null) { say("У характеристики ответов нет CCCD"); disconnect(); return }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                run {
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    g.writeDescriptor(cccd)
                }
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                say("Не удалось подписаться на ответы модуля, код $status")
                disconnect()
                return
            }
            _link.value = Link.READY
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int
        ) {
            busy = false
            if (status != BluetoothGatt.GATT_SUCCESS) say("Модуль отверг запись, код $status")
            pump()
        }

        // Android 13 и новее отдают содержимое отдельным параметром;
        // на прежних оно лежит в самой характеристике.
        override fun onCharacteristicChanged(
            g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray
        ) {
            if (c.uuid == VescProtocol.EVT) push(VescProtocol.parse(value))
        }

        @Deprecated("Оставлено ради Android 12 и старше")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            val v = c.value ?: return
            if (c.uuid == VescProtocol.EVT) push(VescProtocol.parse(v))
        }
    }
}
