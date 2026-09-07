package ru.vesc.ble

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Шов между экранами и транспортом.
 *
 * Экраны и вся логика сеанса разговаривают только с этим интерфейсом, а
 * что за ним — настоящий BLE или имитатор прошивки — им безразлично.
 * Благодаря этому весь путь пользователя, включая неверный пароль,
 * блокировку после трёх попыток и истечение минутного окна, гоняется на
 * столе, без беготни к самокату.
 */

data class FoundModule(val id: String, val name: String, val rssi: Int)

enum class Link { IDLE, SCANNING, CONNECTING, READY, LOST }

interface ModuleClient {
    val link: StateFlow<Link>
    val found: StateFlow<List<FoundModule>>
    val events: SharedFlow<VescProtocol.Event>

    fun startScan()
    fun stopScan()
    fun connect(id: String)
    fun disconnect()
    fun send(frame: ByteArray)

    /** Есть только у имитатора: снять и подать питание. */
    fun powerCycle() {}
    val isFake: Boolean get() = false
}
