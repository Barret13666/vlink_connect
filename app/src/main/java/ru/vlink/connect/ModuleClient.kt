package ru.vlink.connect

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Шов между экранами и BLE. Экраны и вся логика сеанса разговаривают только
 * с этим интерфейсом и ничего не знают ни про BluetoothGatt, ни про
 * разрешения.
 */

data class FoundModule(val id: String, val name: String, val rssi: Int)

enum class Link { IDLE, SCANNING, CONNECTING, READY, LOST }

interface ModuleClient {
    val link: StateFlow<Link>
    val found: StateFlow<List<FoundModule>>
    val events: SharedFlow<VescProtocol.Event>

    /** Человеческие сообщения о том, что пошло не так с самой связью:
     *  выключен Bluetooth, устройство оказалось не тем, связь оборвалась.
     *  Ошибки протокола сюда не попадают — те приходят кадрами RESULT. */
    val notice: SharedFlow<String>

    fun startScan()
    fun stopScan()
    fun connect(id: String)
    fun disconnect()
    fun send(frame: ByteArray)
}
