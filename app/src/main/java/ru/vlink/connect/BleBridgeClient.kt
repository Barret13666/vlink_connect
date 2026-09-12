package ru.vlink.connect

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
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
 * Связь с мостом UART-дисплея.
 *
 * Устроен так же, как BleModuleClient, и по тем же причинам: очередь
 * записей, потому что Android не выполняет две операции GATT разом, и одна
 * сопрограмма на все события, чтобы порядок доставки совпадал с порядком,
 * в котором мост их прислал.
 *
 * Отличие одно, зато существенное. Этот клиент живёт ОДНОВРЕМЕННО с
 * клиентом модуля: во время привязки телефон держит оба соединения сразу.
 * Поэтому здесь свой BluetoothGatt, свой Handler и своя очередь — общими
 * они начали бы мешать друг другу на ровном месте.
 *
 * Задержки перед discoverServices тут нет намеренно. У модуля она нужна,
 * потому что для привязанного устройства Android в этот момент поднимает
 * шифрование и опрос таблицы вперемешку с этим даёт ошибку 133. Мост
 * шифрования не требует: его сервис настройки открыт, и вся его защита —
 * время после включения питания.
 */
@SuppressLint("MissingPermission")
class BleBridgeClient(private val app: Application) : BridgeClient {

    private companion object {
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _link = MutableStateFlow(Link.IDLE)
    override val link: StateFlow<Link> = _link

    private val _events = MutableSharedFlow<BridgeProtocol.Event>(extraBufferCapacity = 32)
    override val events: SharedFlow<BridgeProtocol.Event> = _events

    private val _notice = MutableSharedFlow<String>(extraBufferCapacity = 8)
    override val notice: SharedFlow<String> = _notice

    private val adapter: BluetoothAdapter?
        get() = (app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private var gatt: BluetoothGatt? = null
    private var ctrl: BluetoothGattCharacteristic? = null
    private var evt: BluetoothGattCharacteristic? = null

    private val outbox = ArrayDeque<ByteArray>()
    private var busy = false
    private var closing = false

    private fun say(text: String) { scope.launch { _notice.emit(text) } }
    private fun push(e: BridgeProtocol.Event) { scope.launch { _events.emit(e) } }

    // ------------------------------------------------------- подключение

    override fun connect(id: String) {
        val a = adapter ?: return say("Bluetooth недоступен")
        if (!a.isEnabled) return say("Включите Bluetooth")
        disconnect()
        closing = false
        _link.value = Link.CONNECTING
        gatt = a.getRemoteDevice(id)
            .connectGatt(app, false, callback, android.bluetooth.BluetoothDevice.TRANSPORT_LE)
    }

    override fun disconnect() {
        closing = true
        outbox.clear()
        busy = false
        ctrl = null
        evt = null
        gatt?.let { it.disconnect(); it.close() }
        gatt = null
        if (_link.value != Link.IDLE) _link.value = Link.IDLE
    }

    // ---------------------------------------------------------- отправка

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
            say("Команда не ушла в мост")
        }
    }

    // ---------------------------------------------------- обратные вызовы

    private val callback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                g.discoverServices()
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
                        say("Не удалось подключиться к мосту, код $status")
                    }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val service = g.getService(BridgeProtocol.SERVICE)
            if (service == null) {
                say("У этого устройства нет сервиса настройки — похоже, " +
                    "это мост со старой прошивкой, без поддержки привязки")
                disconnect()
                return
            }
            ctrl = service.getCharacteristic(BridgeProtocol.CTRL)
            evt = service.getCharacteristic(BridgeProtocol.EVT)
            val e = evt
            if (ctrl == null || e == null) {
                say("Сервис настройки моста неполный")
                disconnect()
                return
            }

            g.setCharacteristicNotification(e, true)
            val cccd = e.getDescriptor(CCCD)
            if (cccd == null) { say("У характеристики ответов моста нет CCCD"); disconnect(); return }

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
                say("Не удалось подписаться на ответы моста, код $status")
                disconnect()
                return
            }
            _link.value = Link.READY
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int
        ) {
            busy = false
            if (status != BluetoothGatt.GATT_SUCCESS) say("Мост отверг запись, код $status")
            pump()
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray
        ) {
            if (c.uuid == BridgeProtocol.EVT) push(BridgeProtocol.parse(value))
        }

        @Deprecated("Оставлено ради Android 12 и старше")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            val v = c.value ?: return
            if (c.uuid == BridgeProtocol.EVT) push(BridgeProtocol.parse(v))
        }
    }
}
