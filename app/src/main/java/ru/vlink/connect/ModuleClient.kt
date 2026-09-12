package ru.vlink.connect

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Шов между экранами и BLE. Экраны и вся логика сеанса разговаривают только
 * с этими интерфейсами и ничего не знают ни про BluetoothGatt, ни про
 * разрешения.
 */

/** Что за устройство нашлось. Различать нужно с самого поиска: модуль и мост
 *  отвечают разным сервисам, и предложить человеку «войти» в мост было бы
 *  бессмысленно. */
enum class DeviceKind { MODULE, BRIDGE }

data class FoundModule(
    val id: String,
    val name: String,
    val rssi: Int,
    val kind: DeviceKind = DeviceKind.MODULE
)

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

/**
 * Связь с мостом UART-дисплея. Отдельный объект, а не режим прежнего
 * клиента, и вот почему: во время привязки телефон держит ДВА соединения
 * сразу — с модулем и с мостом. Одно на двоих состояние тут же начало бы
 * путаться, а Android две независимые сессии GATT переваривает спокойно.
 *
 * Поиск сюда не входит: мосты приезжают тем же сканером, что и модули,
 * просто с другим kind.
 */
interface BridgeClient {
    val link: StateFlow<Link>
    val events: SharedFlow<BridgeProtocol.Event>
    val notice: SharedFlow<String>

    fun connect(id: String)
    fun disconnect()
    fun send(frame: ByteArray)
}
