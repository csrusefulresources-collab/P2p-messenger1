package com.csr.nearbyconnect

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Bluetooth Classic RFCOMM transport. Only one active peer is supported in v1.0. */
class BluetoothPeer(private val adapter: BluetoothAdapter, private val listener: Listener) {
    interface Listener {
        fun onStatus(message: String)
        fun onMessage(message: String, mine: Boolean)
        fun onVoiceNote(bytes: ByteArray)
        fun onCallAudio(bytes: ByteArray)
        fun onCallState(active: Boolean)
    }

    companion object {
        private val APP_UUID: UUID = UUID.fromString("b8b2c1c0-4b3a-4c2d-9e1f-4d6a7b8c9d10")
        private const val MAX_PACKET = 8 * 1024 * 1024
    }

    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var output: DataOutputStream? = null
    private val callActive = AtomicBoolean(false)
    private val writing = Any()

    @SuppressLint("MissingPermission")
    fun listen() {
        Thread {
            var server: BluetoothServerSocket? = null
            try {
                server = adapter.listenUsingRfcommWithServiceRecord("Nearby Connect", APP_UUID)
                listener.onStatus("Waiting for a Bluetooth connection…")
                val accepted = server.accept()
                attach(accepted)
            } catch (e: Exception) {
                listener.onStatus("Bluetooth listening failed: ${e.message ?: "Unknown error"}")
            } finally { try { server?.close() } catch (_: Exception) {} }
        }.start()
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        Thread {
            try {
                adapter.cancelDiscovery()
                val candidate = device.createRfcommSocketToServiceRecord(APP_UUID)
                candidate.connect()
                attach(candidate)
            } catch (e: Exception) {
                listener.onStatus("Could not connect: ${e.message ?: "Check pairing and try again"}")
            }
        }.start()
    }

    private fun attach(newSocket: BluetoothSocket) {
        try {
            socket?.close()
            socket = newSocket
            output = DataOutputStream(newSocket.outputStream)
            listener.onStatus("Connected to ${newSocket.remoteDevice.name ?: "Bluetooth device"}")
            val input = DataInputStream(newSocket.inputStream)
            Thread {
                try {
                    while (true) {
                        val type = input.readInt()
                        val size = input.readInt()
                        if (size < 0 || size > MAX_PACKET) throw IOException("Invalid packet size")
                        val data = ByteArray(size)
                        input.readFully(data)
                        when (type) {
                            1 -> listener.onMessage(String(data, Charsets.UTF_8), false)
                            2 -> listener.onVoiceNote(data)
                            3 -> listener.onCallAudio(data)
                            4 -> { callActive.set(true); listener.onCallState(true); listener.onStatus("Incoming audio call") }
                            5 -> { callActive.set(false); listener.onCallState(false); listener.onStatus("Call ended") }
                        }
                    }
                } catch (_: Exception) {
                    callActive.set(false)
                    listener.onCallState(false)
                    listener.onStatus("Bluetooth connection closed")
                    try { socket?.close() } catch (_: Exception) {}
                    socket = null; output = null
                }
            }.start()
        } catch (e: Exception) { listener.onStatus("Connection error: ${e.message}") }
    }

    private fun sendPacket(type: Int, bytes: ByteArray) {
        try {
            synchronized(writing) {
                val stream = output ?: throw IOException("Not connected")
                stream.writeInt(type); stream.writeInt(bytes.size); stream.write(bytes); stream.flush()
            }
        } catch (e: Exception) { listener.onStatus("Send failed: ${e.message ?: "Not connected"}") }
    }

    fun sendMessage(message: String) = sendPacket(1, message.toByteArray(Charsets.UTF_8))
    fun sendVoiceNote(bytes: ByteArray) = sendPacket(2, bytes)
    fun startCall() { callActive.set(true); sendPacket(4, ByteArray(0)); listener.onCallState(true) }
    fun endCall() { callActive.set(false); sendPacket(5, ByteArray(0)); listener.onCallState(false) }
    fun sendCallAudio(bytes: ByteArray) { if (callActive.get()) sendPacket(3, bytes) }
    fun isCallActive() = callActive.get()
    fun isConnected(): Boolean = try { socket?.isConnected == true } catch (_: Exception) { false }
    fun disconnect() { callActive.set(false); try { socket?.close() } catch (_: Exception) {}; socket = null; output = null; listener.onCallState(false); listener.onStatus("Disconnected") }
}
